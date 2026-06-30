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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@AllArgsConstructor
@Service
public class PrenotazioniService {

    private final GraphServiceClient graphClient;
    private final SaleService saleService;

    public List<PrenotazioneDTO> getPrenotazioni(String salaEmail, String dataInizio, String dataFine, Jwt jwt) {
        log.info("Richiesta prenotazioni per sala {} dal {} al {}", salaEmail, dataInizio, dataFine);
        String filter = String.format(
                "start/dateTime ge '%s' and end/dateTime le '%s'",
                dataInizio, dataFine
        );

        var events = graphClient.users()
                .byUserId(salaEmail)
                .calendar()
                .events()
                .get(req -> {
                    req.queryParameters.filter = filter;
                    req.queryParameters.top = 500;
                })
                .getValue();

        log.debug("Trovati {} eventi per la sala {}", events.size(), salaEmail);

        return events.stream()
                .map(event -> mapToDTO(event, salaEmail, jwt))
                .collect(Collectors.toList());
    }
    
    public List<PrenotazioneDTO> getPrenotazioniTutteSale(String dataInizio, String dataFine, Jwt jwt) {
        log.info("Richiesta prenotazioni per tutte le sale dal {} al {}", dataInizio, dataFine);
        List<SalaDTO> tutteLeSale = saleService.getSale(); 

        return tutteLeSale.stream()
                .<PrenotazioneDTO>flatMap(sala -> {
                    try {
                        List<PrenotazioneDTO> lista = getPrenotazioni(sala.getEmail(), dataInizio, dataFine, jwt);
                        return lista.stream();
                    } catch (Exception e) {
                        log.error("Errore nel recupero prenotazioni per la sala {}: {}", sala.getEmail(), e.getMessage(), e);
                        return Stream.<PrenotazioneDTO>empty();
                    }
                })
                .collect(Collectors.toList());
    }

    public PrenotazioneDTO creaPrenotazione(PrenotazioneRequestDTO request, Jwt jwt) {
        log.info("Creazione prenotazione per sala {} - titolo: {}", request.getSalaEmail(), request.getTitolo());
        verificaAdmin(jwt);
        validaOrariPrenotazione(request.getStart(), request.getEnd());  

        var event = buildEvent(request);
        var createdEvent = graphClient.users()
                .byUserId(request.getSalaEmail())
                .calendar()
                .events()
                .post(event);

        log.info("Prenotazione creata con ID {}", createdEvent.getId());
        return mapToDTO(createdEvent, request.getSalaEmail(), jwt);
    }

    public PrenotazioneDTO modificaPrenotazione(String eventId, PrenotazioneRequestDTO request, Jwt jwt) {
        log.info("Modifica prenotazione ID {} per sala {}", eventId, request.getSalaEmail());
        verificaAdmin(jwt);
        validaOrariPrenotazione(request.getStart(), request.getEnd());
        
        var event = buildEvent(request);
        var updatedEvent = graphClient.users()
                .byUserId(request.getSalaEmail())
                .events()
                .byEventId(eventId)
                .patch(event);

        log.info("Prenotazione {} modificata con successo", eventId);
        return mapToDTO(updatedEvent, request.getSalaEmail(), jwt);
    }

    public void cancellaPrenotazione(String eventId, String salaEmail, Jwt jwt) {
        log.info("Cancellazione prenotazione ID {} per sala {}", eventId, salaEmail);
        verificaAdmin(jwt);

        graphClient.users()
                .byUserId(salaEmail)
                .events()
                .byEventId(eventId)
                .delete();

        log.info("Prenotazione {} cancellata con successo", eventId);
    }

    // --- Metodi privati ---

    private Event buildEvent(PrenotazioneRequestDTO request) {
        log.debug("Costruzione evento: titolo={}, start={}, end={}", request.getTitolo(), request.getStart(), request.getEnd());
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

        return PrenotazioneDTO.builder()
                .id(event.getId())
                .salaId(salaEmail != null ? salaEmail.trim().toLowerCase() : null)
                .salaEmail(salaEmail)
                .titolo(event.getSubject())
                .descrizione(event.getBody() != null ? event.getBody().getContent() : null)
                .start(event.getStart() != null ? convertiInEuropeRome(event.getStart()) : null)
                .end(event.getEnd() != null ? convertiInEuropeRome(event.getEnd()) : null)
                
                .organizzatoreNome(event.getOrganizer() != null
                        && event.getOrganizer().getEmailAddress() != null
                        ? event.getOrganizer().getEmailAddress().getName()
                        : null)
                .modificabile(isAdmin)  
                .build();
    }
    
    private String convertiInEuropeRome(DateTimeTimeZone dtz) {
        if (dtz == null || dtz.getDateTime() == null) return null;
        
        String dateTime = dtz.getDateTime();
        String timeZone = dtz.getTimeZone();
        
        // Se la data ha già un offset esplicito (+HH:MM) o Z, parsiamo direttamente
        if (dateTime.endsWith("Z") || dateTime.matches(".*[+-]\\d{2}:\\d{2}$")) {
            ZonedDateTime zdt = ZonedDateTime.parse(dateTime);
            return zdt.withZoneSameInstant(ZoneId.of("Europe/Rome"))
                      .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        
        // Altrimenti usa il timezone specificato da Graph (default UTC se null)
        ZoneId zoneId = timeZone != null ? ZoneId.of(timeZone) : ZoneId.of("UTC");
        ZonedDateTime zdt = LocalDateTime.parse(dateTime).atZone(zoneId);
        return zdt.withZoneSameInstant(ZoneId.of("Europe/Rome"))
                  .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
    
    private void validaOrariPrenotazione(String startStr, String endStr) {
        try {
            LocalDateTime start = LocalDateTime.parse(startStr);
            LocalDateTime end = LocalDateTime.parse(endStr);

            // 1. La fine non può essere prima (o uguale) all'inizio
            if (end.isBefore(start) || end.isEqual(start)) {
                log.warn("Validazione fallita: end={} prima o uguale a start={}", end, start);
                throw new IllegalArgumentException("Errore: l'orario di fine deve essere successivo all'orario di inizio.");
            }

            // 2. Controllo fascia lavorativa (08:00 - 20:00)
            LocalTime startTime = start.toLocalTime();
            LocalTime endTime = end.toLocalTime();
            LocalTime minTime = LocalTime.of(8, 0);
            LocalTime maxTime = LocalTime.of(20, 0);

            if (startTime.isBefore(minTime) || endTime.isAfter(maxTime)) {
                log.warn("Validazione fallita: orario {} - {} fuori fascia 08:00-20:00", startTime, endTime);
                throw new IllegalArgumentException("Errore: le prenotazioni sono consentite solo nella fascia oraria 08:00 - 20:00.");
            }
        } catch (DateTimeParseException e) {
            log.warn("Validazione fallita: formato data non valido: start={}, end={}", startStr, endStr);
            throw new IllegalArgumentException("Errore: Formato data/ora non valido. Usa il formato ISO-8601.");
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
}