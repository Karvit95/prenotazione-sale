package it.szn.prenotazionesale.service;

import it.szn.prenotazionesale.graph.GraphCalendarGateway;
import it.szn.prenotazionesale.mapper.PrenotazioneMapper;
import it.szn.prenotazionesale.validator.PrenotazioneValidator;

import com.microsoft.graph.models.DateTimeTimeZone;
import com.microsoft.graph.models.Event;
import com.microsoft.graph.models.PatternedRecurrence;
import com.microsoft.graph.models.RecurrencePattern;
import com.microsoft.graph.models.RecurrenceRange;
import com.microsoft.kiota.ApiException;
import it.szn.prenotazionesale.exception.GraphPaginationException;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.model.PrenotazioniTutteSaleResponse;
import it.szn.prenotazionesale.model.SalaNonDisponibileDTO;
import it.szn.prenotazionesale.config.AppConfig;
import it.szn.prenotazionesale.model.SalaDTO;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Servizio per la gestione operativa delle prenotazioni delle sale riunioni.
 * Interagisce con Microsoft 365 Exchange Online tramite le API Microsoft Graph.
 *
 * Responsabilità principali:
 * - Consultazione degli eventi e delle disponibilità (tramite gateway per espansione ricorrenze).
 * - Creazione, aggiornamento e cancellazione di prenotazioni singole e serie
 * ricorrenti.
 * - Migrazione atomica di eventi tra sale diverse (cambio sala) con logica di
 * rollback compensativo.
 * - Sincronizzazione thread-safe per singola sala tramite lock rientranti con
 * timeout.
 */
@Slf4j
@Service
public class PrenotazioniService {

    private final GraphCalendarGateway calendarGateway;
    private final SaleService saleService;
    private final AppConfig appConfig;
    private final ThreadPoolTaskExecutor executorGraphParallelo;
    private final PrenotazioneValidator validator;
    private final PrenotazioneMapper mapper;

    /**
     * Mappa dei lock rientranti per singola sala.
     * Serializza le operazioni di verifica disponibilità e successiva scrittura
     * tramite gateway Microsoft Graph per la medesima sala, prevenendo race condition
     * da prenotazioni simultanee sovrapposte (TOCTOU).
     * Ogni lock opera in memoria locale con timeout per prevenire blocchi indefiniti dei thread.
     */
    private final ConcurrentHashMap<String, ReentrantLock> lockPerSala = new ConcurrentHashMap<>();

    public PrenotazioniService(
            GraphCalendarGateway calendarGateway,
            SaleService saleService,
            AppConfig appConfig,
            @Qualifier("graphQueryExecutor") ThreadPoolTaskExecutor executorGraphParallelo,
            PrenotazioneValidator validator,
            PrenotazioneMapper mapper) {
        this.calendarGateway = calendarGateway;
        this.saleService = saleService;
        this.appConfig = appConfig;
        this.executorGraphParallelo = executorGraphParallelo;
        this.validator = validator;
        this.mapper = mapper;
    }

    private static final Pattern PATTERN_ICALUID_SICURO = Pattern.compile("^[A-Za-z0-9._~@+=/-]{1,255}$");
    private static final Pattern PATTERN_EMAIL_SICURA = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /**
     * Controllo BOLA / IDOR: assicura che l'indirizzo email appartenga esclusivamente
     * a una casella di sala censita in Exchange, bloccando tentativi di Confused Deputy
     * verso calendari personali di utenti o dirigenti del tenant.
     */
    private void validaSalaAutorizzata(String salaEmail) {
        if (salaEmail == null || salaEmail.isBlank()) {
            throw new IllegalArgumentException("L'indirizzo email della sala non puo essere vuoto.");
        }
        String chiave = salaEmail.trim().toLowerCase();
        if (!PATTERN_EMAIL_SICURA.matcher(chiave).matches()) {
            log.warn("Formato salaEmail non valido o tentativo di injection: {}", salaEmail);
            throw new IllegalArgumentException(
                    "Formato indirizzo non valido: la sala specificata non e autorizzata o inesistente.");
        }
        if (lockPerSala.containsKey(chiave)) {
            return;
        }
        boolean autorizzata = saleService.getSale().stream()
                .anyMatch(s -> s.getEmail() != null && s.getEmail().trim().equalsIgnoreCase(chiave));
        if (!autorizzata) {
            log.warn("Tentativo di accesso BOLA a casella non autorizzata: {}", salaEmail);
            throw new AccessDeniedException(
                    "Errore di autorizzazione: la sala specificata non e autorizzata o inesistente.");
        }
    }

    /**
     * Recupera le prenotazioni confermate per una specifica sala nell'intervallo
     * temporale richiesto.
     * Utilizza il gateway Graph per espandere automaticamente tutte
     * le occorrenze delle serie ricorrenti nel periodo indicato, convertite nel
     * fuso Europe/Rome.
     *
     * @param salaEmail  indirizzo email della casella di sala Exchange
     * @param dataInizio estremo inferiore dell'intervallo temporale (formato
     *                   ISO-8601)
     * @param dataFine   estremo superiore dell'intervallo temporale (formato
     *                   ISO-8601)
     * @param jwt        token JWT del chiamante per la verifica dei privilegi di
     *                   modifica
     * @return lista delle prenotazioni presenti nel periodo
     */
    public List<PrenotazioneDTO> getPrenotazioni(String salaEmail, String dataInizio, String dataFine, Jwt jwt) {
        log.info("Richiesta prenotazioni per sala {} dal {} al {}", salaEmail, dataInizio, dataFine);

        validaSalaAutorizzata(salaEmail);
        validator.validaIntervalloDate(dataInizio, dataFine);

        String salaNormalizzata = salaEmail.trim().toLowerCase();
        var events = calendarGateway.getEventi(salaNormalizzata, dataInizio, dataFine);

        log.debug("Trovati {} eventi per la sala {}", events.size(), salaNormalizzata);

        boolean modificabile = isAdmin(jwt);
        return events.stream().map(event -> mapper.mapToDTO(event, salaNormalizzata, modificabile)).collect(Collectors.toList());
    }

    /**
     * Recupera in parallelo le prenotazioni di tutte le sale gestite
     * nell'intervallo specificato con supporto ai risultati parziali.
     * Esegue chiamate asincrone concorrenti su pool dedicato con timeout di 12 secondi per sala.
     * Se almeno una sala ha successo, restituisce le prenotazioni disponibili e la lista delle
     * sale fallite (con motivo generico). Se tutte le sale falliscono, rilancia l'errore originale.
     *
     * @param dataInizio estremo inferiore dell'intervallo temporale
     * @param dataFine   estremo superiore dell'intervallo temporale
     * @param jwt        token JWT del chiamante
     * @return risposta aggregata con prenotazioni, sale non disponibili ed eventuale flag parziale
     */
    public PrenotazioniTutteSaleResponse getPrenotazioniTutteSale(String dataInizio, String dataFine, Jwt jwt) {
        log.info("Richiesta prenotazioni per tutte le sale dal {} al {}", dataInizio, dataFine);
        validator.validaIntervalloDate(dataInizio, dataFine);

        List<SalaDTO> tutteLeSale = saleService.getSale();
        if (tutteLeSale == null || tutteLeSale.isEmpty()) {
            return PrenotazioniTutteSaleResponse.builder()
                    .prenotazioni(Collections.emptyList())
                    .saleNonDisponibili(Collections.emptyList())
                    .parziale(false)
                    .totaleSale(0)
                    .build();
        }

        record SalaTask(SalaDTO sala, CompletableFuture<List<PrenotazioneDTO>> future) {}

        List<SalaTask> tasks = tutteLeSale.stream()
                .map(sala -> {
                    CompletableFuture<List<PrenotazioneDTO>> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            return getPrenotazioni(sala.getEmail(), dataInizio, dataFine, jwt);
                        } catch (ApiException e) {
                            log.error("Errore Graph API (status {}) per sala {}: {}", e.getResponseStatusCode(),
                                    sala.getEmail(), e.getMessage());
                            throw e;
                        } catch (Exception e) {
                            log.error("Errore recupero prenotazioni per sala {}: {}", sala.getEmail(), e.getMessage(), e);
                            throw new RuntimeException(
                                    "Errore recupero prenotazioni per sala " + sala.getNome() + ": " + e.getMessage(), e);
                        }
                    }, executorGraphParallelo)
                    .orTimeout(12, TimeUnit.SECONDS);
                    return new SalaTask(sala, future);
                })
                .collect(Collectors.toList());

        // Attende il completamento di tutti i task senza propagare anticipatamente eccezioni
        CompletableFuture.allOf(tasks.stream().map(SalaTask::future).toArray(CompletableFuture[]::new))
                .exceptionally(ex -> null)
                .join();

        List<PrenotazioneDTO> allPrenotazioni = new ArrayList<>();
        List<SalaNonDisponibileDTO> saleNonDisponibili = new ArrayList<>();
        SalaTask primoTaskFallito = null;

        for (SalaTask task : tasks) {
            try {
                List<PrenotazioneDTO> prenotazioni = task.future().join();
                if (prenotazioni != null) {
                    allPrenotazioni.addAll(prenotazioni);
                }
            } catch (Throwable t) {
                if (primoTaskFallito == null) {
                    primoTaskFallito = task;
                }
                String motivo = classifyError(t);
                saleNonDisponibili.add(SalaNonDisponibileDTO.builder()
                        .salaEmail(task.sala().getEmail())
                        .salaNome(task.sala().getNome())
                        .motivo(motivo)
                        .build());
            }
        }

        // Se tutte le sale hanno fallito, rilancia l'errore originale del primo task fallito
        if (saleNonDisponibili.size() == tasks.size()) {
            primoTaskFallito.future().join();
        }

        boolean parziale = !saleNonDisponibili.isEmpty();
        return PrenotazioniTutteSaleResponse.builder()
                .prenotazioni(allPrenotazioni)
                .saleNonDisponibili(saleNonDisponibili)
                .parziale(parziale)
                .totaleSale(tutteLeSale.size())
                .build();
    }

    private String classifyError(Throwable t) {
        Throwable curr = t;
        while (curr != null) {
            if (curr instanceof TimeoutException) {
                return "TIMEOUT";
            }
            if (curr instanceof ApiException || curr instanceof GraphPaginationException) {
                return "ERRORE_GRAPH";
            }
            curr = curr.getCause();
        }
        return "ERRORE_INTERNO";
    }

    /**
     * Crea una nuova prenotazione (singola o serie ricorrente) per una sala.
     * L'operazione acquisisce il lock esclusivo sulla sala target con timeout di 10
     * secondi,
     * verifica l'assenza di sovrapposizioni orarie e registra l'evento su Microsoft
     * Graph.
     *
     * @param request dati della prenotazione da creare
     * @param jwt     token JWT autenticato per il controllo di autorizzazione admin
     * @return DTO dell'evento creato
     */
    public PrenotazioneDTO creaPrenotazione(PrenotazioneRequestDTO request, Jwt jwt) {
        log.info("Creazione prenotazione per sala {} - intervallo: {} -> {}", request.getSalaEmail(),
                request.getStart(), request.getEnd());
        verificaAdmin(jwt);
        validaSalaAutorizzata(request.getSalaEmail());
        validator.validaContenutoTestuale(request);
        validator.validaOrariPrenotazione(request.getStart(), request.getEnd());

        if (request.getPattern() != null) {
            validator.validaRicorrenza(request);
        }

        return conLockSala(request.getSalaEmail(), () -> {
            verificaDisponibilita(request, null, jwt);
            var event = mapper.buildEvent(request);
            var createdEvent = calendarGateway.creaEvento(request.getSalaEmail(), event);

            log.info("Prenotazione creata con ID {}", createdEvent.getId());
            return mapper.mapToDTO(createdEvent, request.getSalaEmail(), isAdmin(jwt));
        });
    }

    /**
     * Modifica una prenotazione esistente, gestendo sia l'aggiornamento all'interno
     * della stessa sala
     * sia la migrazione su una sala differente (cambio sala).
     *
     * In caso di Cambio Sala:
     * - Acquisisce i lock di entrambe le sale in ordine lessicografico per evitare
     * deadlock.
     * - Crea il nuovo evento nella sala di destinazione (duplicando la serie dal
     * master per gli eventi ricorrenti).
     * - Cancella il vecchio evento dalla sala di origine.
     * - Se la cancellazione fallisce, esegue un rollback compensativo rimuovendo il
     * nuovo evento creato.
     *
     * In caso di Modifica sulla Stessa Sala:
     * - Per le serie ricorrenti, aggiorna il Series Master preservando start ed end
     * se l'orario non è variato,
     * così da mantenere intatte le singole
     * eccezioni già configurate nella
     * serie.
     * - Per le singole occorrenze, aggiorna i dati specifici della data
     * selezionata.
     *
     * @param eventId identificativo univoco dell'evento o occorrenza su Graph
     * @param request payload con i dettagli aggiornati della prenotazione
     * @param jwt     token JWT autenticato per il controllo privilegi
     * @return DTO dell'evento aggiornato
     */
    public PrenotazioneDTO modificaPrenotazione(String eventId, PrenotazioneRequestDTO request, Jwt jwt) {
        log.info("Modifica prenotazione ID {} per sala {}", eventId, request.getSalaEmail());
        verificaAdmin(jwt);
        boolean modificabile = isAdmin(jwt);
        validaSalaAutorizzata(request.getSalaEmail());
        if (request.getSalaEmailOriginale() != null && !request.getSalaEmailOriginale().isBlank()) {
            validaSalaAutorizzata(request.getSalaEmailOriginale());
        }
        validator.validaContenutoTestuale(request);
        validator.validaOrariPrenotazione(request.getStart(), request.getEnd());

        String salaOriginale = request.getSalaEmailOriginale();
        if (salaOriginale != null && !salaOriginale.equals(request.getSalaEmail())) {
            return modificaConCambioSala(eventId, request, jwt, modificabile);
        } else {
            return modificaNellaStessaSala(eventId, request, jwt, modificabile);
        }
    }

    /**
     * Gestisce la migrazione atomica di una prenotazione (singola o serie) tra due sale differenti,
     * serializzando l'accesso tramite lock ordinati lessicograficamente.
     */
    private PrenotazioneDTO modificaConCambioSala(String eventId, PrenotazioneRequestDTO request, Jwt jwt,
            boolean modificabile) {
        String salaOriginale = request.getSalaEmailOriginale();
        String primaSala = salaOriginale.compareTo(request.getSalaEmail()) < 0 ? salaOriginale : request.getSalaEmail();
        String secondaSala = salaOriginale.compareTo(request.getSalaEmail()) < 0 ? request.getSalaEmail() : salaOriginale;

        ReentrantLock lock1 = getLockPerSala(primaSala);
        ReentrantLock lock2 = getLockPerSala(secondaSala);

        boolean lock1Acquired = false;
        boolean lock2Acquired = false;
        try {
            lock1Acquired = lock1.tryLock(10, TimeUnit.SECONDS);
            if (!lock1Acquired) {
                throw new IllegalStateException("Timeout nell'acquisizione del lock per la sala: " + primaSala);
            }
            lock2Acquired = lock2.tryLock(10, TimeUnit.SECONDS);
            if (!lock2Acquired) {
                throw new IllegalStateException("Timeout nell'acquisizione del lock per la sala: " + secondaSala);
            }
            log.info("Sala cambiata da {} a {}: creo il nuovo evento prima di cancellare il vecchio", salaOriginale,
                    request.getSalaEmail());

            boolean modificaSerie = "SERIE".equals(request.getTipoModifica());
            boolean haSeriesMaster = request.getSeriesMasterId() != null && !request.getSeriesMasterId().isBlank();

            PrenotazioneDTO nuovaPrenotazione;
            if (modificaSerie && haSeriesMaster) {
                nuovaPrenotazione = ricreaSerieInNuovaSala(request, jwt, modificabile);
            } else if (!modificaSerie && haSeriesMaster) {
                // Cambio sala su singola occorrenza: genera un evento singolo non ricorrente nella nuova sala
                nuovaPrenotazione = creaPrenotazione(request, jwt);
            } else {
                // Evento singolo standard: crea la prenotazione nella nuova sala
                nuovaPrenotazione = creaPrenotazione(request, jwt);
            }

            String idDaCancellare = (modificaSerie && haSeriesMaster) ? request.getSeriesMasterId() : eventId;
            eliminaOriginaleConRollback(salaOriginale, idDaCancellare, request, nuovaPrenotazione);

            return nuovaPrenotazione;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Operazione di cambio sala interrotta", ie);
        } finally {
            if (lock2Acquired) {
                lock2.unlock();
            }
            if (lock1Acquired) {
                lock1.unlock();
            }
        }
    }

    /**
     * Ricrea una serie ricorrente completa nella nuova sala di destinazione clonando
     * orari e pattern dal master originale.
     */
    private PrenotazioneDTO ricreaSerieInNuovaSala(PrenotazioneRequestDTO request, Jwt jwt, boolean modificabile) {
        String salaOriginale = request.getSalaEmailOriginale();
        log.info("Modifica serie su cambio sala: recupero master originale ID {} dalla sala {}",
                request.getSeriesMasterId(), salaOriginale);

        var masterOriginale = calendarGateway.getEvento(salaOriginale, request.getSeriesMasterId());
        PrenotazioneRequestDTO checkRequest = costruisciCheckRequestDaMaster(masterOriginale, request.getSalaEmail());
        verificaDisponibilita(checkRequest, null, jwt);

        var nuovoEvento = new Event();
        mapper.impostaTitoloEDescrizione(nuovoEvento, request);

        var start = new DateTimeTimeZone();
        start.setDateTime(masterOriginale.getStart().getDateTime());
        start.setTimeZone("Europe/Rome");
        nuovoEvento.setStart(start);

        var end = new DateTimeTimeZone();
        end.setDateTime(masterOriginale.getEnd().getDateTime());
        end.setTimeZone("Europe/Rome");
        nuovoEvento.setEnd(end);

        if (masterOriginale.getRecurrence() != null) {
            nuovoEvento.setRecurrence(clonaRicorrenza(masterOriginale.getRecurrence()));
        }

        var createdEvent = calendarGateway.creaEvento(request.getSalaEmail(), nuovoEvento);
        log.info("Nuova serie creata con ID {} nella sala {} (originata dal master {})",
                createdEvent.getId(), request.getSalaEmail(), request.getSeriesMasterId());

        return mapper.mapToDTO(createdEvent, request.getSalaEmail(), modificabile);
    }

    /**
     * Costruisce il DTO temporaneo per verificare la disponibilità della sala di destinazione
     * basandosi sulla pianificazione del master originale.
     */
    private PrenotazioneRequestDTO costruisciCheckRequestDaMaster(Event masterOriginale, String salaDestinazione) {
        PrenotazioneRequestDTO checkRequest = new PrenotazioneRequestDTO();
        checkRequest.setSalaEmail(salaDestinazione);
        checkRequest.setStart(masterOriginale.getStart().getDateTime());
        checkRequest.setEnd(masterOriginale.getEnd().getDateTime());
        if (masterOriginale.getRecurrence() != null && masterOriginale.getRecurrence().getPattern() != null) {
            var p = masterOriginale.getRecurrence().getPattern();
            checkRequest.setPattern(p.getType() != null ? p.getType().name().toLowerCase() : null);
            checkRequest.setIntervallo(p.getInterval());
            if (p.getDaysOfWeek() != null) {
                checkRequest.setGiorniSettimana(
                        p.getDaysOfWeek().stream().map(d -> d.name()).collect(Collectors.toList()));
            }
        }
        if (masterOriginale.getRecurrence() != null && masterOriginale.getRecurrence().getRange() != null) {
            checkRequest.setDataFine(masterOriginale.getRecurrence().getRange().getEndDate() != null
                    ? masterOriginale.getRecurrence().getRange().getEndDate().toString()
                    : null);
        }
        return checkRequest;
    }

    /**
     * Clona ricorsivamente la struttura di ricorrenza (pattern e range) di un evento Microsoft Graph.
     */
    private PatternedRecurrence clonaRicorrenza(PatternedRecurrence recurrenceOriginale) {
        var nuovaRecurrence = new PatternedRecurrence();
        if (recurrenceOriginale.getPattern() != null) {
            var nuovoPattern = new RecurrencePattern();
            nuovoPattern.setType(recurrenceOriginale.getPattern().getType());
            nuovoPattern.setInterval(recurrenceOriginale.getPattern().getInterval());
            if (recurrenceOriginale.getPattern().getDaysOfWeek() != null) {
                nuovoPattern.setDaysOfWeek(recurrenceOriginale.getPattern().getDaysOfWeek());
            }
            if (recurrenceOriginale.getPattern().getDayOfMonth() != null) {
                nuovoPattern.setDayOfMonth(recurrenceOriginale.getPattern().getDayOfMonth());
            }
            nuovaRecurrence.setPattern(nuovoPattern);
        }
        if (recurrenceOriginale.getRange() != null) {
            var nuovoRange = new RecurrenceRange();
            nuovoRange.setType(recurrenceOriginale.getRange().getType());
            nuovoRange.setStartDate(recurrenceOriginale.getRange().getStartDate());
            nuovoRange.setEndDate(recurrenceOriginale.getRange().getEndDate());
            nuovoRange.setNumberOfOccurrences(recurrenceOriginale.getRange().getNumberOfOccurrences());
            nuovaRecurrence.setRange(nuovoRange);
        }
        return nuovaRecurrence;
    }

    /**
     * Elimina l'evento originale sulla sala di partenza con rollback compensativo
     * in caso di fallimento, per scongiurare duplicati tra sale.
     */
    private void eliminaOriginaleConRollback(String salaOriginale, String idDaCancellare,
            PrenotazioneRequestDTO request, PrenotazioneDTO nuovaPrenotazione) {
        try {
            calendarGateway.eliminaEvento(salaOriginale, idDaCancellare);
        } catch (Exception e) {
            // Rollback compensativo: elimina l'evento appena creato per evitare la persistenza di duplicati
            log.error(
                    "Cancellazione evento originale {} su sala {} fallita. Tento rollback dell'evento creato {} su sala {}",
                    idDaCancellare, salaOriginale, nuovaPrenotazione.getId(), request.getSalaEmail(), e);
            try {
                calendarGateway.eliminaEvento(request.getSalaEmail(), nuovaPrenotazione.getId());
                log.info("Rollback completato con successo: eliminato evento {}", nuovaPrenotazione.getId());
            } catch (Exception rollbackEx) {
                log.error("[SECURITY_INCIDENT - ROLLBACK_FAILURE] Rollback fallito per ID {} su sala {}: {}",
                        nuovaPrenotazione.getId(), request.getSalaEmail(), rollbackEx.getMessage());
            }
            throw new IllegalStateException(
                    "Impossibile completare il cambio sala: cancellazione fallita sulla sala precedente.", e);
        }
    }

    /**
     * Esegue la modifica di un evento all'interno della stessa sala sotto lock esclusivo.
     */
    private PrenotazioneDTO modificaNellaStessaSala(String eventId, PrenotazioneRequestDTO request, Jwt jwt,
            boolean modificabile) {
        return conLockSala(request.getSalaEmail(), () -> {
            boolean modificaSerie = "SERIE".equals(request.getTipoModifica());
            String seriesMasterIdDaEscludere = modificaSerie
                    ? ((request.getSeriesMasterId() != null && !request.getSeriesMasterId().isBlank())
                            ? request.getSeriesMasterId()
                            : eventId)
                    : null;
            verificaDisponibilita(request, eventId, seriesMasterIdDaEscludere, jwt);

            if (modificaSerie) {
                return modificaSerie(eventId, request, modificabile);
            } else {
                return modificaOccorrenzaSingola(eventId, request, modificabile);
            }
        });
    }

    private record OrariSerie(String start, String end, boolean orarioModificato) {}

    /**
     * Calcola gli orari start/end preservando la data d'inizio originale del master della serie.
     * Se l'orario giornaliero è invariato, orarioModificato è false per non inviare start/end nel PATCH.
     */
    private OrariSerie calcolaOrariSerie(PrenotazioneRequestDTO request, String salaEmail, String idSuCuiOperare) {
        String nuovoStartDateTime;
        String nuovoEndDateTime;
        boolean orarioModificato = true;

        try {
            var existingMaster = calendarGateway.getEvento(salaEmail, idSuCuiOperare);
            String dataOriginaleMaster = existingMaster.getStart().getDateTime().substring(0, 10);

            LocalDateTime reqStart = LocalDateTime.parse(request.getStart().substring(0, 19));
            LocalDateTime reqEnd = LocalDateTime.parse(request.getEnd().substring(0, 19));
            long durataMinuti = java.time.Duration.between(reqStart, reqEnd).toMinutes();

            String nuovoOrario = request.getStart().substring(11, 19);
            nuovoStartDateTime = dataOriginaleMaster + "T" + nuovoOrario;

            LocalDateTime nuovoEndLDT = LocalDateTime.parse(dataOriginaleMaster + "T" + nuovoOrario)
                    .plusMinutes(durataMinuti);
            nuovoEndDateTime = nuovoEndLDT.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

            String masterStartOriginale = existingMaster.getStart() != null
                    ? existingMaster.getStart().getDateTime()
                    : "";
            if (masterStartOriginale.length() >= 19
                    && masterStartOriginale.substring(11, 19).equals(nuovoOrario)) {
                orarioModificato = false;
            }

            log.debug("Modifica SERIE: data master {} con orario {} -> start={}, end={}, orarioModificato={}",
                    dataOriginaleMaster, nuovoOrario, nuovoStartDateTime, nuovoEndDateTime, orarioModificato);
        } catch (Exception e) {
            log.warn(
                    "Impossibile recuperare il master per preservare la data originale, uso i valori della request: {}",
                    e.getMessage());
            nuovoStartDateTime = request.getStart();
            nuovoEndDateTime = request.getEnd();
        }

        return new OrariSerie(nuovoStartDateTime, nuovoEndDateTime, orarioModificato);
    }

    /**
     * Aggiorna il Series Master di una serie ricorrente tramite PATCH su Microsoft Graph.
     */
    private PrenotazioneDTO modificaSerie(String eventId, PrenotazioneRequestDTO request, boolean modificabile) {
        String idSuCuiOperare = (request.getSeriesMasterId() != null && !request.getSeriesMasterId().isBlank())
                ? request.getSeriesMasterId()
                : eventId;

        var event = new Event();
        mapper.impostaTitoloEDescrizione(event, request);

        OrariSerie orari = calcolaOrariSerie(request, request.getSalaEmail(), idSuCuiOperare);
        if (orari.orarioModificato()) {
            var start = new DateTimeTimeZone();
            start.setDateTime(orari.start());
            start.setTimeZone("Europe/Rome");
            event.setStart(start);

            var end = new DateTimeTimeZone();
            end.setDateTime(orari.end());
            end.setTimeZone("Europe/Rome");
            event.setEnd(end);
        }

        var updatedEvent = calendarGateway.aggiornaEvento(request.getSalaEmail(), idSuCuiOperare, event);
        log.info("Prenotazione (serie, ID {}) modificata con successo", idSuCuiOperare);
        return mapper.mapToDTO(updatedEvent, request.getSalaEmail(), modificabile);
    }

    /**
     * Aggiorna una singola istanza/occorrenza di una serie ricorrente su Microsoft Graph.
     */
    private PrenotazioneDTO modificaOccorrenzaSingola(String eventId, PrenotazioneRequestDTO request,
            boolean modificabile) {
        var event = new Event();
        mapper.impostaTitoloEDescrizione(event, request);

        var start = new DateTimeTimeZone();
        start.setDateTime(request.getStart());
        start.setTimeZone("Europe/Rome");
        event.setStart(start);

        var end = new DateTimeTimeZone();
        end.setDateTime(request.getEnd());
        end.setTimeZone("Europe/Rome");
        event.setEnd(end);

        var updatedEvent = calendarGateway.aggiornaEvento(request.getSalaEmail(), eventId, event);
        log.info("Prenotazione (occorrenza singola) {} modificata con successo", eventId);
        return mapper.mapToDTO(updatedEvent, request.getSalaEmail(), modificabile);
    }

    /**
     * Cancella una prenotazione o una serie ricorrente.
     * Overload di comodità per default
     * tipo 'SERIE'.
     */
    public void cancellaPrenotazione(String eventId, String salaEmail, Jwt jwt) {
        cancellaPrenotazione(eventId, salaEmail, "SERIE", null, jwt);
    }

    /**
     * Cancella una prenotazione o una serie ricorrente dal calendario Exchange.
     * Per una serie ricorrente completa, elimina il series master (rimuovendo
     * l'intera pianificazione);
     * per una singola occorrenza, elimina esclusivamente l'evento della data
     * indicata.
     *
     * @param eventId           identificativo dell'evento o dell'occorrenza
     * @param salaEmail         email della casella di sala
     * @param tipoCancellazione "SERIE" o "SINGOLA"
     * @param seriesMasterId    identificativo del master della serie se disponibile
     * @param jwt               token JWT autenticato per il controllo privilegi
     */
    public void cancellaPrenotazione(String eventId, String salaEmail, String tipoCancellazione, String seriesMasterId,
            Jwt jwt) {
        log.info("Cancellazione prenotazione ID {} per sala {} (tipo: {}, seriesMasterId: {})", eventId, salaEmail,
                tipoCancellazione, seriesMasterId);
        verificaAdmin(jwt);
        validator.validaTipoCancellazione(tipoCancellazione);

        conLockSala(salaEmail, () -> {
            String idSuCuiOperare = "SERIE".equals(tipoCancellazione) && seriesMasterId != null
                    && !seriesMasterId.isBlank()
                            ? seriesMasterId
                            : eventId;

            // Recupera l'evento dalla sala per verificare se l'organizzatore è un utente esterno/Outlook
            Event eventoDaCancellare = null;
            try {
                eventoDaCancellare = calendarGateway.getEvento(salaEmail, idSuCuiOperare,
                        "id", "isOrganizer", "organizer", "iCalUId", "seriesMasterId", "start", "end");
            } catch (Exception e) {
                log.warn("Impossibile recuperare metadati preliminari dell'evento {} per sala {}: {}", idSuCuiOperare,
                        salaEmail, e.getMessage());
            }

            boolean cancellatoDaOrganizzatore = false;
            if (eventoDaCancellare != null) {
                boolean isOrganizer = Boolean.TRUE.equals(eventoDaCancellare.getIsOrganizer());
                String organizerEmail = (eventoDaCancellare.getOrganizer() != null
                        && eventoDaCancellare.getOrganizer().getEmailAddress() != null)
                                ? eventoDaCancellare.getOrganizer().getEmailAddress().getAddress()
                                : null;
                String iCalUId = eventoDaCancellare.getICalUId();

                // Se la sala non e l'organizzatore (evento creato da Outlook personale di un
                // utente)
                if (!isOrganizer && organizerEmail != null && !organizerEmail.equalsIgnoreCase(salaEmail)
                        && iCalUId != null && !iCalUId.isBlank()) {
                    if (!PATTERN_ICALUID_SICURO.matcher(iCalUId).matches()) {
                        log.warn(
                                "UID evento {} contiene caratteri non validi per il filtro OData. Cancellazione automatica organizzatore ignorata.",
                                idSuCuiOperare);
                    } else {
                        log.info(
                                "L'evento {} appartiene al calendario personale di {} (iCalUId: {}). Rimozione alla radice.",
                                idSuCuiOperare, organizerEmail, iCalUId);
                        try {
                            final String oDataFilter = "iCalUId eq '" + validator.escapeODataString(iCalUId) + "'";

                            List<Event> eventiOrganizzatore = calendarGateway.cercaEventiPerFiltro(organizerEmail, oDataFilter);

                            if (eventiOrganizzatore != null && !eventiOrganizzatore.isEmpty()) {
                                for (Event orgEvent : eventiOrganizzatore) {
                                    if (iCalUId.equalsIgnoreCase(orgEvent.getICalUId())) {
                                        calendarGateway.eliminaEvento(organizerEmail, orgEvent.getId());
                                        log.info(
                                                "Evento {} cancellato con successo dal calendario dell'organizzatore {}",
                                                orgEvent.getId(), organizerEmail);
                                    }
                                }
                                cancellatoDaOrganizzatore = true;
                            }
                        } catch (Exception exOrg) {
                            log.warn(
                                    "Impossibile cancellare l'evento dal calendario dell'organizzatore {}: {}. Si procede con la rimozione diretta dalla sala.",
                                    organizerEmail, exOrg.getMessage());
                        }
                    }
                }
            }

            // Rimuove la copia dalla sala (se non già rimossa a catena da Exchange)
            try {
                calendarGateway.eliminaEvento(salaEmail, idSuCuiOperare);
                log.info("Prenotazione {} cancellata dalla sala {} (tipo richiesto: {})", idSuCuiOperare, salaEmail,
                        tipoCancellazione);
            } catch (ApiException apiEx) {
                if (apiEx.getResponseStatusCode() == 404 && cancellatoDaOrganizzatore) {
                    log.info(
                            "L'evento {} della sala {} risulta già rimosso da Exchange in seguito alla cancellazione dell'organizzatore.",
                            idSuCuiOperare, salaEmail);
                } else {
                    throw apiEx;
                }
            }
        });
    }

    // --- Metodi privati di supporto ---

    /**
     * Verifica la disponibilità della sala per la prenotazione richiesta, escludendo l'evento in modifica.
     */
    private void verificaDisponibilita(PrenotazioneRequestDTO request, String eventIdDaEscludere, Jwt jwt) {
        verificaDisponibilita(request, eventIdDaEscludere, null, jwt);
    }

    /**
     * Verifica che nessuna delle occorrenze generate dalla richiesta si sovrapponga
     * a eventi esistenti nella sala.
     * Esclude dal confronto l'identificativo dell'evento singolo o del master della
     * serie in corso di modifica.
     */
    private void verificaDisponibilita(PrenotazioneRequestDTO request, String eventIdDaEscludere,
            String seriesMasterIdDaEscludere, Jwt jwt) {
        String salaEmail = request.getSalaEmail();

        List<LocalDateTime[]> occorrenze = validator.generaOccorrenze(request);
        if (occorrenze.isEmpty())
            return;

        LocalDateTime startReq = validator.parseIsoToRomeLocalDateTime(request.getStart());
        LocalDateTime endReq = validator.parseIsoToRomeLocalDateTime(request.getEnd());
        LocalDateTime primoInizio = occorrenze.stream().map(o -> o[0]).min(Comparator.naturalOrder()).orElse(startReq);
        LocalDateTime ultimaFine = occorrenze.stream().map(o -> o[1]).max(Comparator.naturalOrder()).orElse(endReq);

        DateTimeFormatter formatoFiltro = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        String filtroInizio = primoInizio.toLocalDate().atStartOfDay().format(formatoFiltro);
        String filtroFine = ultimaFine.toLocalDate().plusDays(1).atStartOfDay().format(formatoFiltro);

        List<PrenotazioneDTO> eventiEsistenti = getPrenotazioni(salaEmail, filtroInizio, filtroFine, jwt);

        for (LocalDateTime[] occorrenza : occorrenze) {
            LocalDateTime nuovoStart = occorrenza[0];
            LocalDateTime nuovoEnd = occorrenza[1];

            boolean conflitto = eventiEsistenti.stream()
                    .filter(e -> {
                        if (eventIdDaEscludere != null && e.getId().equals(eventIdDaEscludere)) {
                            return false;
                        }
                        if (seriesMasterIdDaEscludere != null) {
                            if (seriesMasterIdDaEscludere.equals(e.getId()))
                                return false;
                            if (seriesMasterIdDaEscludere.equals(e.getSeriesMasterId()))
                                return false;
                        }
                        return true;
                    })
                    .anyMatch(e -> {
                        if (e.getStart() == null || e.getEnd() == null)
                            return false;

                        String startPulito = e.getStart().length() > 19 ? e.getStart().substring(0, 19) : e.getStart();
                        String endPulito = e.getEnd().length() > 19 ? e.getEnd().substring(0, 19) : e.getEnd();

                        LocalDateTime esistenteStart = LocalDateTime.parse(startPulito);
                        LocalDateTime esistenteEnd = LocalDateTime.parse(endPulito);

                        return esistenteStart.isBefore(nuovoEnd) && esistenteEnd.isAfter(nuovoStart);
                    });

            if (conflitto) {
                log.warn("Conflitto rilevato: sala {} gia occupata in data {}", salaEmail, nuovoStart.toLocalDate());
                throw new IllegalArgumentException(
                        String.format("Errore: la sala risulta gia occupata il %s nella fascia oraria richiesta.",
                                nuovoStart.toLocalDate().toString()));
            }
        }
    }

    /**
     * Controlla i privilegi dell'utente richiedente, sollevando
     * AccessDeniedException se non è un amministratore.
     */
    private void verificaAdmin(Jwt jwt) {
        if (!isAdmin(jwt)) {
            log.warn("Accesso negato: utente non admin tenta operazione riservata");
            throw new AccessDeniedException("Solo gli admin possono eseguire questa operazione");
        }
    }

    /**
     * Verifica la presenza del ruolo di amministratore all'interno dei claims del
     * token JWT,
     * leggendo il ruolo configurato nell'ambiente (app.admin-role).
     */
    private boolean isAdmin(Jwt jwt) {
        if (jwt == null) {
            return false;
        }
        List<String> ruoli = jwt.getClaimAsStringList("roles");
        String expectedRole = (appConfig != null)
                ? appConfig.resolveAdminRole()
                : AppConfig.DEFAULT_ADMIN_ROLE;
        return ruoli != null && ruoli.contains(expectedRole);
    }

    /**
     * Recupera o inizializza il ReentrantLock associato a una sala, verificando
     * preventivamente
     * che la sala esista e appartenga all'elenco delle sale gestite da Exchange.
     */
    private ReentrantLock getLockPerSala(String salaEmail) {
        validaSalaAutorizzata(salaEmail);
        String chiave = salaEmail.trim().toLowerCase();
        return lockPerSala.computeIfAbsent(chiave, k -> new ReentrantLock());
    }

    /**
     * Esegue un'operazione con valore di ritorno sotto il lock esclusivo per la sala indicata.
     */
    private <T> T conLockSala(String salaEmail, Supplier<T> operazione) {
        ReentrantLock lock = getLockPerSala(salaEmail);
        boolean lockAcquired = false;
        try {
            lockAcquired = lock.tryLock(10, TimeUnit.SECONDS);
            if (!lockAcquired) {
                throw new IllegalStateException("Timeout acquisizione lock per la sala: " + salaEmail);
            }
            return operazione.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Operazione interrotta durante l'acquisizione del lock", ie);
        } finally {
            if (lockAcquired) {
                lock.unlock();
            }
        }
    }

    /**
     * Esegue un'operazione senza valore di ritorno sotto il lock esclusivo per la sala indicata.
     */
    private void conLockSala(String salaEmail, Runnable operazione) {
        conLockSala(salaEmail, () -> {
            operazione.run();
            return null;
        });
    }
}
