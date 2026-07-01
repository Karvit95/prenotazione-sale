package it.szn.prenotazionesale.service;

import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.model.SalaDTO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@AllArgsConstructor
@Service
public class PrenotazioniService {

	private final GraphServiceClient graphClient;
	private final SaleService saleService;

	// Un lock per sala: serializza il blocco "verifica disponibilità + scrittura
	// evento"
	// così due richieste concorrenti sulla stessa sala non possono superare
	// entrambe il
	// controllo di disponibilità e creare eventi sovrapposti (TOCTOU / race
	// condition).
	//
	// NB: funziona solo con una singola istanza dell'applicazione in esecuzione (il
	// lock
	// vive in memoria, non è condiviso tra più repliche). Va bene per il deployment
	// attuale
	// a singola istanza; se in futuro si passa a più repliche, questo lock non
	// basta più
	// e serve un lock distribuito (es. su un DB condiviso).
	private final ConcurrentHashMap<String, ReentrantLock> lockPerSala = new ConcurrentHashMap<>();

	public List<PrenotazioneDTO> getPrenotazioni(String salaEmail, String dataInizio, String dataFine, Jwt jwt) {
		log.info("Richiesta prenotazioni per sala {} dal {} al {}", salaEmail, dataInizio, dataFine);
		String filter = String.format("start/dateTime ge '%s' and end/dateTime le '%s'", dataInizio, dataFine);

		var events = graphClient.users().byUserId(salaEmail).calendar().events().get(req -> {
			req.queryParameters.filter = filter;
			req.queryParameters.top = 500;
			req.headers.add("prefer", "outlook.timezone=\"Europe/Rome\"");
		}).getValue();

		log.debug("Trovati {} eventi per la sala {}", events.size(), salaEmail);

		return events.stream().map(event -> mapToDTO(event, salaEmail, jwt)).collect(Collectors.toList());
	}

	public List<PrenotazioneDTO> getPrenotazioniTutteSale(String dataInizio, String dataFine, Jwt jwt) {
		log.info("Richiesta prenotazioni per tutte le sale dal {} al {}", dataInizio, dataFine);
		List<SalaDTO> tutteLeSale = saleService.getSale();

		return tutteLeSale.parallelStream().<PrenotazioneDTO>flatMap(sala -> {
			try {
				List<PrenotazioneDTO> lista = getPrenotazioni(sala.getEmail(), dataInizio, dataFine, jwt);
				return lista.stream();
			} catch (Exception e) {
				log.error("Errore nel recupero prenotazioni per la sala {}: {}", sala.getEmail(), e.getMessage(), e);
				return Stream.<PrenotazioneDTO>empty();
			}
		}).collect(Collectors.toList());
	}

	public PrenotazioneDTO creaPrenotazione(PrenotazioneRequestDTO request, Jwt jwt) {
		log.info("Creazione prenotazione per sala {} - titolo: {}", request.getSalaEmail(), request.getTitolo());
		verificaAdmin(jwt);
		validaOrariPrenotazione(request.getStart(), request.getEnd());

		ReentrantLock lock = getLockPerSala(request.getSalaEmail());
		lock.lock();

		try {
			verificaDisponibilita(request.getSalaEmail(), request.getStart(), request.getEnd(), null, jwt);
			var event = buildEvent(request);
			var createdEvent = graphClient.users().byUserId(request.getSalaEmail()).calendar().events().post(event);

			log.info("Prenotazione creata con ID {}", createdEvent.getId());
			return mapToDTO(createdEvent, request.getSalaEmail(), jwt);
		} finally {
			lock.unlock();
		}
	}

	public PrenotazioneDTO modificaPrenotazione(String eventId, PrenotazioneRequestDTO request, Jwt jwt) {
		log.info("Modifica prenotazione ID {} per sala {}", eventId, request.getSalaEmail());
		verificaAdmin(jwt);
		validaOrariPrenotazione(request.getStart(), request.getEnd());

		String salaOriginale = request.getSalaEmailOriginale();

		// Se la sala è cambiata, cancella dalla vecchia e crea sulla nuova
		if (salaOriginale != null && !salaOriginale.equals(request.getSalaEmail())) {
			log.info("Sala cambiata da {} a {}: creo il nuovo evento prima di cancellare il vecchio", salaOriginale,
					request.getSalaEmail());

			PrenotazioneDTO nuovaPrenotazione = creaPrenotazione(request, jwt);

			try {
				cancellaPrenotazione(eventId, salaOriginale, jwt);
			} catch (Exception e) {
				log.error(
						"Prenotazione spostata con ID nuovo {} ma impossibile cancellare l'evento originale {} sulla sala {}: rimane una prenotazione duplicata da rimuovere manualmente",
						nuovaPrenotazione.getId(), eventId, salaOriginale, e);
				throw new IllegalStateException("La prenotazione è stata creata sulla nuova sala (ID "
						+ nuovaPrenotazione.getId()
						+ "), ma non è stato possibile rimuovere quella originale sulla sala precedente (ID " + eventId
						+ "). Contatta l'amministratore IT per la cancellazione manuale della vecchia prenotazione.",
						e);
			}

			return nuovaPrenotazione;
		}

		ReentrantLock lock = getLockPerSala(request.getSalaEmail());
		lock.lock();

		try {
			verificaDisponibilita(request.getSalaEmail(), request.getStart(), request.getEnd(), eventId, jwt);

			// Sala invariata → PATCH normale
			var event = buildEvent(request);
			var updatedEvent = graphClient.users().byUserId(request.getSalaEmail()).events().byEventId(eventId)
					.patch(event);

			log.info("Prenotazione {} modificata con successo", eventId);
			return mapToDTO(updatedEvent, request.getSalaEmail(), jwt);

		} finally {

			lock.unlock();

		}
	}

	public void cancellaPrenotazione(String eventId, String salaEmail, Jwt jwt) {
		log.info("Cancellazione prenotazione ID {} per sala {}", eventId, salaEmail);
		verificaAdmin(jwt);

		ReentrantLock lock = getLockPerSala(salaEmail);
		lock.lock();

		try {
			graphClient.users().byUserId(salaEmail).events().byEventId(eventId).delete();

			log.info("Prenotazione {} cancellata con successo", eventId);

		} finally {
			lock.unlock();
		}

	}

	// --- Metodi privati ---

	private Event buildEvent(PrenotazioneRequestDTO request) {
		log.debug("Costruzione evento: titolo={}, start={}, end={}", request.getTitolo(), request.getStart(),
				request.getEnd());
		var event = new Event();
		event.setSubject(request.getTitolo());

		if (request.getDescrizione() != null) {
			var body = new ItemBody();
			body.setContent(request.getDescrizione());
			body.setContentType(BodyType.Text);
			event.setBody(body);
		}

		var start = new DateTimeTimeZone();
		start.setDateTime(request.getStart());
		start.setTimeZone("Europe/Rome");
		event.setStart(start);

		var end = new DateTimeTimeZone();
		end.setDateTime(request.getEnd());
		end.setTimeZone("Europe/Rome");
		event.setEnd(end);

		return event;
	}

	private PrenotazioneDTO mapToDTO(Event event, String salaEmail, Jwt jwt) {
		boolean isAdmin = isAdmin(jwt);

		return PrenotazioneDTO.builder().id(event.getId())
				.salaId(salaEmail != null ? salaEmail.trim().toLowerCase() : null).salaEmail(salaEmail)
				.titolo(event.getSubject()).descrizione(event.getBody() != null ? event.getBody().getContent() : null)
				.start(event.getStart() != null ? event.getStart().getDateTime() : null)
				.end(event.getEnd() != null ? event.getEnd().getDateTime() : null)
				.organizzatoreNome(event.getOrganizer() != null && event.getOrganizer().getEmailAddress() != null
						? event.getOrganizer().getEmailAddress().getName()
						: null)
				.modificabile(isAdmin).build();
	}

	private void validaOrariPrenotazione(String startStr, String endStr) {
		try {
			LocalDateTime start = LocalDateTime.parse(startStr);
			LocalDateTime end = LocalDateTime.parse(endStr);

			// 1. La fine non può essere prima (o uguale) all'inizio
			if (end.isBefore(start) || end.isEqual(start)) {
				log.warn("Validazione fallita: end={} prima o uguale a start={}", end, start);
				throw new IllegalArgumentException(
						"Errore: l'orario di fine deve essere successivo all'orario di inizio.");
			}

		} catch (DateTimeParseException e) {
			log.warn("Validazione fallita: formato data non valido: start={}, end={}", startStr, endStr);
			throw new IllegalArgumentException("Errore: Formato data/ora non valido. Usa il formato ISO-8601.");
		}
	}

	private void verificaDisponibilita(String salaEmail, String startStr, String endStr, String eventIdDaEscludere,
			Jwt jwt) {
		log.debug("Verifica disponibilità sala {} dal {} al {} (esclude eventId={})", salaEmail, startStr, endStr,
				eventIdDaEscludere);

		LocalDateTime nuovoStart = LocalDateTime.parse(startStr);
		LocalDateTime nuovoEnd = LocalDateTime.parse(endStr);

		// Recuperiamo gli eventi del giorno interessato (margine ampio per coprire
		// eventi a cavallo)
		DateTimeFormatter formatoFiltro = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
		String filtroInizio = nuovoStart.toLocalDate().atStartOfDay().format(formatoFiltro);
		String filtroFine = nuovoStart.toLocalDate().plusDays(1).atStartOfDay().format(formatoFiltro);

		List<PrenotazioneDTO> eventiEsistenti = getPrenotazioni(salaEmail, filtroInizio, filtroFine, jwt);

		boolean conflitto = eventiEsistenti.stream()
				.filter(e -> eventIdDaEscludere == null || !e.getId().equals(eventIdDaEscludere)).anyMatch(e -> {
					if (e.getStart() == null || e.getEnd() == null)
						return false;
					LocalDateTime esistenteStart = LocalDateTime.parse(e.getStart());
					LocalDateTime esistenteEnd = LocalDateTime.parse(e.getEnd());
					// Overlap se: inizio esistente prima della fine nuova E fine esistente dopo
					// l'inizio nuovo
					return esistenteStart.isBefore(nuovoEnd) && esistenteEnd.isAfter(nuovoStart);
				});

		if (conflitto) {
			log.warn("Conflitto rilevato: sala {} già occupata dal {} al {}", salaEmail, startStr, endStr);
			throw new IllegalArgumentException("Errore: la sala è già occupata nella fascia oraria selezionata.");
		}
	}

	// --- Metodi helper per permessi ---

	private void verificaAdmin(Jwt jwt) {
		if (!isAdmin(jwt)) {
			log.warn("Accesso negato: utente non admin tenta operazione riservata");
			throw new AccessDeniedException("Solo gli admin possono eseguire questa operazione");
		}
	}

	private boolean isAdmin(Jwt jwt) {
		List<String> ruoli = jwt.getClaimAsStringList("roles");
		return ruoli != null && ruoli.contains("RoomBooking.Admin");
	}

	private ReentrantLock getLockPerSala(String salaEmail) {
		String chiave = salaEmail.trim().toLowerCase();
		return lockPerSala.computeIfAbsent(chiave, k -> new ReentrantLock());
	}
}