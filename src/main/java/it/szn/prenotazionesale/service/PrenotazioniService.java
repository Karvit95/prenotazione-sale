package it.szn.prenotazionesale.service;

import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.config.AppConfig;
import it.szn.prenotazionesale.model.SalaDTO;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Servizio per la gestione operativa delle prenotazioni delle sale riunioni.
 * Interagisce con Microsoft 365 Exchange Online tramite le API Microsoft Graph.
 *
 * ResponsabilitÃ  principali:
 * - Consultazione degli eventi e delle disponibilitÃ  (tramite calendarView per espansione ricorrenze).
 * - Creazione, aggiornamento e cancellazione di prenotazioni singole e serie ricorrenti.
 * - Migrazione atomica di eventi tra sale diverse (cambio sala) con logica di rollback compensativo.
 * - Sincronizzazione thread-safe per singola sala tramite lock rientranti con timeout.
 */
@Slf4j
@AllArgsConstructor
@Service
public class PrenotazioniService {

    private final GraphServiceClient graphClient;
    private final SaleService saleService;
    private final AppConfig appConfig;

    /**
     * Mappa dei lock rientranti per singola sala.
     * Serializza le operazioni di verifica disponibilitÃ  e successiva scrittura su Microsoft Graph
     * per la medesima sala, prevenendo race condition da prenotazioni simultanee sovrapposte (TOCTOU).
     * Ogni lock opera in memoria locale con timeout per prevenire blocchi indefiniti dei thread.
     */
    private final ConcurrentHashMap<String, ReentrantLock> lockPerSala = new ConcurrentHashMap<>();

    /**
     * Thread pool dedicato alle interrogazioni parallele verso Microsoft Graph per il recupero
     * simultaneo degli eventi di tutte le sale (getPrenotazioniTutteSale).
     * Trattandosi di operazioni I/O-bound (chiamate HTTP remote), utilizza un pool dimensionato
     * per evitare colli di bottiglia o saturazione del pool principale dell'applicazione.
     */
    private final ExecutorService executorGraphParallelo = Executors.newFixedThreadPool(20);

    /**
     * Rilascia le risorse dell'executor dedicato in fase di arresto del contesto applicativo.
     */
    @PreDestroy
    public void shutdown() {
        executorGraphParallelo.shutdown();
    }

    /**
     * Pattern per la validazione delle stringhe di data/ora impiegate nei filtri OData di Microsoft Graph.
     * Ammette sia il formato ISO-8601 UTC (con 'Z' o offset) che il formato ISO locale senza offset,
     * bloccando caratteri non consentiti per prevenire injection di sintassi OData non controllata.
     */
    private static final Pattern PATTERN_DATA_FILTRO = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})?$");

    /**
     * Verifica che una stringa temporale rispetti il formato ISO-8601 consentito per i filtri Graph.
     */
    private void validaParametroFiltroData(String valore, String nomeParametro) {
        if (valore == null || !PATTERN_DATA_FILTRO.matcher(valore).matches()) {
            log.warn("Parametro '{}' rifiutato per il filtro prenotazioni: valore non conforme al formato atteso", nomeParametro);
            throw new IllegalArgumentException(
                    "Errore: il parametro '" + nomeParametro + "' deve essere una data/ora in formato ISO-8601 valido.");
        }
    }

    /**
     * Recupera le prenotazioni confermate per una specifica sala nell'intervallo temporale richiesto.
     * Utilizza l'endpoint calendarView di Microsoft Graph per espandere automaticamente tutte
     * le occorrenze delle serie ricorrenti nel periodo indicato, convertite nel fuso Europe/Rome.
     *
     * @param salaEmail indirizzo email della casella di sala Exchange
     * @param dataInizio estremo inferiore dell'intervallo temporale (formato ISO-8601)
     * @param dataFine estremo superiore dell'intervallo temporale (formato ISO-8601)
     * @param jwt token JWT del chiamante per la verifica dei privilegi di modifica
     * @return lista delle prenotazioni presenti nel periodo
     */
    public List<PrenotazioneDTO> getPrenotazioni(String salaEmail, String dataInizio, String dataFine, Jwt jwt) {
        log.info("Richiesta prenotazioni per sala {} dal {} al {}", salaEmail, dataInizio, dataFine);

        validaParametroFiltroData(dataInizio, "dataInizio");
        validaParametroFiltroData(dataFine, "dataFine");

        var events = graphClient.users().byUserId(salaEmail).calendarView().get(req -> {
            req.queryParameters.startDateTime = dataInizio;
            req.queryParameters.endDateTime = dataFine;
            req.headers.add("Prefer", "outlook.timezone=\"Europe/Rome\"");
        }).getValue();

        log.debug("Trovati {} eventi per la sala {}", events.size(), salaEmail);

        return events.stream().map(event -> mapToDTO(event, salaEmail, jwt)).collect(Collectors.toList());
    }

    /**
     * Recupera in parallelo le prenotazioni di tutte le sale gestite nell'intervallo specificato.
     * Esegue chiamate asincrone concorrenti su pool dedicato per ottimizzare i tempi di risposta,
     * aggregando infine tutti gli eventi in una singola lista unificata.
     *
     * @param dataInizio estremo inferiore dell'intervallo temporale
     * @param dataFine estremo superiore dell'intervallo temporale
     * @param jwt token JWT del chiamante
     * @return lista complessiva delle prenotazioni di tutte le sale
     */
    public List<PrenotazioneDTO> getPrenotazioniTutteSale(String dataInizio, String dataFine, Jwt jwt) {
        log.info("Richiesta prenotazioni per tutte le sale dal {} al {}", dataInizio, dataFine);
        List<SalaDTO> tutteLeSale = saleService.getSale();

        List<CompletableFuture<List<PrenotazioneDTO>>> futures = tutteLeSale.stream()
                .map(sala -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return getPrenotazioni(sala.getEmail(), dataInizio, dataFine, jwt);
                    } catch (Exception e) {
                        log.error("Errore nel recupero prenotazioni per la sala {}: {}", sala.getEmail(),
                                e.getMessage(), e);
                        return List.<PrenotazioneDTO>of();
                    }
                }, executorGraphParallelo))
                .collect(Collectors.toList());

        return futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    /**
     * Crea una nuova prenotazione (singola o serie ricorrente) per una sala.
     * L'operazione acquisisce il lock esclusivo sulla sala target con timeout di 10 secondi,
     * verifica l'assenza di sovrapposizioni orarie e registra l'evento su Microsoft Graph.
     *
     * @param request dati della prenotazione da creare
     * @param jwt token JWT autenticato per il controllo di autorizzazione admin
     * @return DTO dell'evento creato
     */
    public PrenotazioneDTO creaPrenotazione(PrenotazioneRequestDTO request, Jwt jwt) {
        log.info("Creazione prenotazione per sala {} - titolo: {}", request.getSalaEmail(), request.getTitolo());
        verificaAdmin(jwt);
        validaOrariPrenotazione(request.getStart(), request.getEnd());

        if (request.getPattern() != null) {
            validaRicorrenza(request);
        }

        ReentrantLock lock = getLockPerSala(request.getSalaEmail());
        boolean lockAcquired = false;
        try {
            lockAcquired = lock.tryLock(10, TimeUnit.SECONDS);
            if (!lockAcquired) {
                throw new IllegalStateException("Timeout acquisizione lock per la sala: " + request.getSalaEmail());
            }
            verificaDisponibilita(request, null, jwt);
            var event = buildEvent(request);
            var createdEvent = graphClient.users().byUserId(request.getSalaEmail()).calendar().events().post(event);

            log.info("Prenotazione creata con ID {}", createdEvent.getId());
            return mapToDTO(createdEvent, request.getSalaEmail(), jwt);
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
     * Modifica una prenotazione esistente, gestendo sia l'aggiornamento all'interno della stessa sala
     * sia la migrazione su una sala differente (cambio sala).
     *
     * In caso di Cambio Sala:
     * - Acquisisce i lock di entrambe le sale in ordine lessicografico per evitare deadlock.
     * - Crea il nuovo evento nella sala di destinazione (duplicando la serie dal master per gli eventi ricorrenti).
     * - Cancella il vecchio evento dalla sala di origine.
     * - Se la cancellazione fallisce, esegue un rollback compensativo rimuovendo il nuovo evento creato.
     *
     * In caso di Modifica sulla Stessa Sala:
     * - Per le serie ricorrenti, aggiorna il Series Master preservando start ed end se l'orario non Ã¨ variato,
     *   cosÃ¬ da mantenere intatte le singole eccezioni giÃ  configurate nella serie.
     * - Per le singole occorrenze, aggiorna i dati specifici della data selezionata.
     *
     * @param eventId identificativo univoco dell'evento o occorrenza su Graph
     * @param request payload con i dettagli aggiornati della prenotazione
     * @param jwt token JWT autenticato per il controllo privilegi
     * @return DTO dell'evento aggiornato
     */
    public PrenotazioneDTO modificaPrenotazione(String eventId, PrenotazioneRequestDTO request, Jwt jwt) {
        log.info("Modifica prenotazione ID {} per sala {}", eventId, request.getSalaEmail());
        verificaAdmin(jwt);
        validaOrariPrenotazione(request.getStart(), request.getEnd());

        String salaOriginale = request.getSalaEmailOriginale();

        // Ramo 1: Cambio sala (migrazione tra due caselle Exchange distinte)
        if (salaOriginale != null && !salaOriginale.equals(request.getSalaEmail())) {
            // Ordinamento lessicografico dei lock delle due sale per scongiurare deadlock tra richieste concorrenti
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
                    // Cambio sala su intera serie: recupera il master originale per clonarne orari, pattern e range
                    log.info("Modifica serie su cambio sala: recupero master originale ID {} dalla sala {}",
                            request.getSeriesMasterId(), salaOriginale);

                    var masterOriginale = graphClient.users().byUserId(salaOriginale).events()
                            .byEventId(request.getSeriesMasterId()).get(req -> {
                                req.headers.add("Prefer", "outlook.timezone=\"Europe/Rome\"");
                            });

                    // Verifica preliminare di disponibilitÃ  della sala di destinazione per l'intera durata della serie
                    PrenotazioneRequestDTO checkRequest = new PrenotazioneRequestDTO();
                    checkRequest.setSalaEmail(request.getSalaEmail());
                    checkRequest.setStart(masterOriginale.getStart().getDateTime());
                    checkRequest.setEnd(masterOriginale.getEnd().getDateTime());
                    if (masterOriginale.getRecurrence() != null && masterOriginale.getRecurrence().getPattern() != null) {
                        var p = masterOriginale.getRecurrence().getPattern();
                        checkRequest.setPattern(p.getType() != null ? p.getType().name().toLowerCase() : null);
                        checkRequest.setIntervallo(p.getInterval());
                        if (p.getDaysOfWeek() != null) {
                            checkRequest.setGiorniSettimana(p.getDaysOfWeek().stream().map(Enum::name).collect(Collectors.toList()));
                        }
                    }
                    if (masterOriginale.getRecurrence() != null && masterOriginale.getRecurrence().getRange() != null) {
                        checkRequest.setDataFine(masterOriginale.getRecurrence().getRange().getEndDate() != null 
                                ? masterOriginale.getRecurrence().getRange().getEndDate().toString() : null);
                    }
                    verificaDisponibilita(checkRequest, null, jwt);

                    // Ricostruzione esplicita della serie ricorrente nella sala di destinazione
                    var nuovoEvento = new Event();
                    nuovoEvento.setSubject(request.getTitolo());

                    if (request.getDescrizione() != null) {
                        var body = new ItemBody();
                        body.setContent(request.getDescrizione());
                        body.setContentType(BodyType.Text);
                        nuovoEvento.setBody(body);
                    }

                    var start = new DateTimeTimeZone();
                    start.setDateTime(masterOriginale.getStart().getDateTime());
                    start.setTimeZone("Europe/Rome");
                    nuovoEvento.setStart(start);

                    var end = new DateTimeTimeZone();
                    end.setDateTime(masterOriginale.getEnd().getDateTime());
                    end.setTimeZone("Europe/Rome");
                    nuovoEvento.setEnd(end);

                    var recurrenceOriginale = masterOriginale.getRecurrence();
                    if (recurrenceOriginale != null && recurrenceOriginale.getPattern() != null) {
                        var nuovaRecurrence = new PatternedRecurrence();

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

                        if (recurrenceOriginale.getRange() != null) {
                            var nuovoRange = new RecurrenceRange();
                            nuovoRange.setType(recurrenceOriginale.getRange().getType());
                            nuovoRange.setStartDate(recurrenceOriginale.getRange().getStartDate());
                            nuovoRange.setEndDate(recurrenceOriginale.getRange().getEndDate());
                            nuovoRange.setNumberOfOccurrences(recurrenceOriginale.getRange().getNumberOfOccurrences());
                            nuovaRecurrence.setRange(nuovoRange);
                        }

                        nuovoEvento.setRecurrence(nuovaRecurrence);
                    }

                    var createdEvent = graphClient.users().byUserId(request.getSalaEmail()).calendar().events()
                            .post(nuovoEvento);

                    log.info("Nuova serie creata con ID {} nella sala {} (originata dal master {})",
                            createdEvent.getId(), request.getSalaEmail(), request.getSeriesMasterId());

                    nuovaPrenotazione = mapToDTO(createdEvent, request.getSalaEmail(), jwt);
                } else if (!modificaSerie && haSeriesMaster) {
                    // Cambio sala su singola occorrenza: genera un evento singolo non ricorrente nella nuova sala
                    nuovaPrenotazione = creaPrenotazione(request, jwt);
                } else {
                    // Evento singolo standard: crea la prenotazione nella nuova sala
                    nuovaPrenotazione = creaPrenotazione(request, jwt);
                }

                String idDaCancellare = (modificaSerie && haSeriesMaster)
                                    ? request.getSeriesMasterId()
                                    : eventId;

                try {
                    graphClient.users().byUserId(salaOriginale).events().byEventId(idDaCancellare).delete();
                } catch (Exception e) {
                    // Rollback compensativo: elimina l'evento appena creato per evitare la persistenza di duplicati
                    log.error("Cancellazione evento originale {} su sala {} fallita. Tento rollback dell'evento creato {} su sala {}",
                            idDaCancellare, salaOriginale, nuovaPrenotazione.getId(), request.getSalaEmail(), e);
                    try {
                        graphClient.users().byUserId(request.getSalaEmail()).events().byEventId(nuovaPrenotazione.getId()).delete();
                        log.info("Rollback completato con successo: eliminato evento {}", nuovaPrenotazione.getId());
                    } catch (Exception rollbackEx) {
                        log.error("Rollback fallito per ID {}: {}", nuovaPrenotazione.getId(), rollbackEx.getMessage());
                    }
                    throw new IllegalStateException("Impossibile completare il cambio sala: cancellazione fallita sulla sala precedente.", e);
                }

                return nuovaPrenotazione;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Operazione di cambio sala interrotta", ie);
            } finally {
                if (lock2Acquired) lock2.unlock();
                if (lock1Acquired) lock1.unlock();
            }
        }

        // Ramo 2: Modifica nella stessa sala
        ReentrantLock lock = getLockPerSala(request.getSalaEmail());
        boolean lockAcquired = false;
        try {
            lockAcquired = lock.tryLock(10, TimeUnit.SECONDS);
            if (!lockAcquired) {
                throw new IllegalStateException("Timeout acquisizione lock per la sala: " + request.getSalaEmail());
            }

            String tipoModifica = request.getTipoModifica();
            boolean modificaSerie = "SERIE".equals(tipoModifica);

            // Esclude dal controllo di disponibilitÃ  l'evento stesso o il master della serie in modifica
            String seriesMasterIdDaEscludere = modificaSerie 
                    ? ((request.getSeriesMasterId() != null && !request.getSeriesMasterId().isBlank()) ? request.getSeriesMasterId() : eventId)
                    : null;
            verificaDisponibilita(request, eventId, seriesMasterIdDaEscludere, jwt);

            if (modificaSerie) {
                // Aggiornamento Series Master su Microsoft Graph
                String idSuCuiOperare = (request.getSeriesMasterId() != null && !request.getSeriesMasterId().isBlank())
                        ? request.getSeriesMasterId()
                        : eventId;

                var event = new Event();
                event.setSubject(request.getTitolo());

                if (request.getDescrizione() != null) {
                    var body = new ItemBody();
                    body.setContent(request.getDescrizione());
                    body.setContentType(BodyType.Text);
                    event.setBody(body);
                }

                String nuovoStartDateTime;
                String nuovoEndDateTime;
                boolean orarioModificato = true;

                try {
                    // Recupera il master per preservarne la data d'inizio originale calcolando il nuovo orario giornaliero
                    var existingMaster = graphClient.users().byUserId(request.getSalaEmail()).events()
                            .byEventId(idSuCuiOperare).get(req -> {
                                req.headers.add("Prefer", "outlook.timezone=\"Europe/Rome\"");
                            });

                    String dataOriginaleMaster = existingMaster.getStart().getDateTime().substring(0, 10);

                    LocalDateTime reqStart = LocalDateTime.parse(request.getStart().substring(0, 19));
                    LocalDateTime reqEnd = LocalDateTime.parse(request.getEnd().substring(0, 19));
                    long durataMinuti = java.time.Duration.between(reqStart, reqEnd).toMinutes();

                    String nuovoOrario = request.getStart().substring(11, 19);
                    nuovoStartDateTime = dataOriginaleMaster + "T" + nuovoOrario;

                    LocalDateTime nuovoEndLDT = LocalDateTime.parse(dataOriginaleMaster + "T" + nuovoOrario)
                            .plusMinutes(durataMinuti);
                    nuovoEndDateTime = nuovoEndLDT.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

                    // Se l'orario Ã¨ invariato (solo titolo o descrizione modificati), non invia start/end
                    // nel PATCH per preservare eventuali eccezioni di date giÃ  modificate nella serie
                    String masterStartOriginale = existingMaster.getStart() != null ? existingMaster.getStart().getDateTime() : "";
                    if (masterStartOriginale.length() >= 19 && masterStartOriginale.substring(11, 19).equals(nuovoOrario)) {
                        orarioModificato = false;
                    }

                    log.debug("Modifica SERIE: data master {} con orario {} -> start={}, end={}, orarioModificato={}",
                            dataOriginaleMaster, nuovoOrario, nuovoStartDateTime, nuovoEndDateTime, orarioModificato);
                } catch (Exception e) {
                    log.warn("Impossibile recuperare il master per preservare la data originale, uso i valori della request: {}",
                            e.getMessage());
                    nuovoStartDateTime = request.getStart();
                    nuovoEndDateTime = request.getEnd();
                }

                if (orarioModificato) {
                    var start = new DateTimeTimeZone();
                    start.setDateTime(nuovoStartDateTime);
                    start.setTimeZone("Europe/Rome");
                    event.setStart(start);

                    var end = new DateTimeTimeZone();
                    end.setDateTime(nuovoEndDateTime);
                    end.setTimeZone("Europe/Rome");
                    event.setEnd(end);
                }

                var updatedEvent = graphClient.users().byUserId(request.getSalaEmail()).events()
                        .byEventId(idSuCuiOperare).patch(event);
                log.info("Prenotazione (serie, ID {}) modificata con successo", idSuCuiOperare);
                return mapToDTO(updatedEvent, request.getSalaEmail(), jwt);
            } else {
                // Aggiornamento della singola occorrenza: aggiorna solo i dettagli di questa specifica istanza
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

                var updatedEvent = graphClient.users().byUserId(request.getSalaEmail()).events().byEventId(eventId)
                        .patch(event);
                log.info("Prenotazione (occorrenza singola) {} modificata con successo", eventId);
                return mapToDTO(updatedEvent, request.getSalaEmail(), jwt);
            }

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
     * Cancella una prenotazione o una serie ricorrente.
     * Overload di comoditÃ  per default tipo 'SERIE'.
     */
    public void cancellaPrenotazione(String eventId, String salaEmail, Jwt jwt) {
        cancellaPrenotazione(eventId, salaEmail, "SERIE", null, jwt);
    }

    /**
     * Cancella una prenotazione o una serie ricorrente dal calendario Exchange.
     * Per una serie ricorrente completa, elimina il series master (rimuovendo l'intera pianificazione);
     * per una singola occorrenza, elimina esclusivamente l'evento della data indicata.
     *
     * @param eventId identificativo dell'evento o dell'occorrenza
     * @param salaEmail email della casella di sala
     * @param tipoCancellazione "SERIE" o "SINGOLA"
     * @param seriesMasterId identificativo del master della serie se disponibile
     * @param jwt token JWT autenticato per il controllo privilegi
     */
    public void cancellaPrenotazione(String eventId, String salaEmail, String tipoCancellazione, String seriesMasterId, Jwt jwt) {
        log.info("Cancellazione prenotazione ID {} per sala {} (tipo: {}, seriesMasterId: {})", eventId, salaEmail,
                tipoCancellazione, seriesMasterId);
        verificaAdmin(jwt);

        ReentrantLock lock = getLockPerSala(salaEmail);
        boolean lockAcquired = false;
        try {
            lockAcquired = lock.tryLock(10, TimeUnit.SECONDS);
            if (!lockAcquired) {
                throw new IllegalStateException("Timeout acquisizione lock per la sala: " + salaEmail);
            }

            String idSuCuiOperare = "SERIE".equals(tipoCancellazione) && seriesMasterId != null && !seriesMasterId.isBlank()
                    ? seriesMasterId
                    : eventId;

            graphClient.users().byUserId(salaEmail).events().byEventId(idSuCuiOperare).delete();
            log.info("Prenotazione {} cancellata dalla sala {} (tipo richiesto: {})", idSuCuiOperare, salaEmail,
                    tipoCancellazione);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Operazione interrotta durante l'acquisizione del lock", ie);
        } finally {
            if (lockAcquired) {
                lock.unlock();
            }
        }
    }

    // --- Metodi privati di supporto ---

    /**
     * Costruisce l'oggetto Event di Microsoft Graph popolando metadati, intervalli orari
     * (con fuso orario fissato a Europe/Rome) ed eventuale regola di ricorrenza (Daily, Weekly, Monthly).
     */
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

        if (request.getPattern() != null && !request.getPattern().isEmpty()) {
            PatternedRecurrence recurrence = new PatternedRecurrence();

            String startDateStr = request.getStart().length() > 10
                    ? request.getStart().substring(0, 10)
                    : request.getStart();
            LocalDate dataInizio = LocalDate.parse(startDateStr);

            RecurrencePattern pattern = new RecurrencePattern();
            pattern.setInterval(request.getIntervallo() != null ? request.getIntervallo() : 1);

            switch (request.getPattern().toLowerCase()) {
                case "daily":
                    pattern.setType(RecurrencePatternType.Daily);
                    break;
                case "weekly":
                    pattern.setType(RecurrencePatternType.Weekly);
                    if (request.getGiorniSettimana() != null && !request.getGiorniSettimana().isEmpty()) {
                        pattern.setDaysOfWeek(request.getGiorniSettimana().stream()
                                .map(giorno -> DayOfWeek.forValue(giorno.toLowerCase()))
                                .collect(Collectors.toList()));
                    } else {
                        String dayName = dataInizio.getDayOfWeek().name().toLowerCase();
                        pattern.setDaysOfWeek(List.of(DayOfWeek.forValue(dayName)));
                    }
                    break;
                case "monthly":
                    pattern.setType(RecurrencePatternType.AbsoluteMonthly);
                    pattern.setDayOfMonth(dataInizio.getDayOfMonth());
                    break;
                default:
                    throw new IllegalArgumentException("Tipo di ricorrenza non supportato: " + request.getPattern());
            }

            recurrence.setPattern(pattern);

            RecurrenceRange range = new RecurrenceRange();
            range.setType(RecurrenceRangeType.EndDate);

            if (request.getDataFine() != null) {
                String dataFineStr = request.getDataFine().length() > 10
                        ? request.getDataFine().substring(0, 10)
                        : request.getDataFine();
                range.setEndDate(LocalDate.parse(dataFineStr));
            } else {
                range.setNumberOfOccurrences(365);
                range.setType(RecurrenceRangeType.Numbered);
            }

            range.setStartDate(dataInizio);
            recurrence.setRange(range);

            event.setRecurrence(recurrence);
            log.debug("Ricorrenza aggiunta: pattern={}, intervallo={}, dataFine={}",
                    request.getPattern(), request.getIntervallo(), request.getDataFine());
        }

        return event;
    }

    /**
     * Mappa un evento Microsoft Graph nel DTO applicativo per il frontend.
     * Estrae identificativi di serie, ripulisce la descrizione da tag HTML e verifica i diritti di modifica.
     */
    private PrenotazioneDTO mapToDTO(Event event, String salaEmail, Jwt jwt) {
        boolean isAdmin = isAdmin(jwt);

        String seriesMasterId = null;
        boolean ricorrente = false;
        String pattern = null;

        if (event.getSeriesMasterId() != null && !event.getSeriesMasterId().isEmpty()) {
            seriesMasterId = event.getSeriesMasterId();
            ricorrente = true;
        }
        if (event.getRecurrence() != null && event.getRecurrence().getPattern() != null) {
            ricorrente = true;
            RecurrencePatternType patternType = event.getRecurrence().getPattern().getType();
            if (patternType != null) {
                switch (patternType) {
                    case Daily: pattern = "daily"; break;
                    case Weekly: pattern = "weekly"; break;
                    case AbsoluteMonthly: pattern = "monthly"; break;
                    default: pattern = patternType.toString().toLowerCase();
                }
            }
        }

        String descrizionePulita = null;
        if (event.getBody() != null && event.getBody().getContent() != null) {
            descrizionePulita = stripHtml(event.getBody().getContent());
        }

        return PrenotazioneDTO.builder().id(event.getId())
                .salaId(salaEmail != null ? salaEmail.trim().toLowerCase() : null).salaEmail(salaEmail)
                .titolo(event.getSubject()).descrizione(descrizionePulita)
                .start(event.getStart() != null ? event.getStart().getDateTime() : null)
                .end(event.getEnd() != null ? event.getEnd().getDateTime() : null)
                .organizzatoreNome(event.getOrganizer() != null && event.getOrganizer().getEmailAddress() != null
                        ? event.getOrganizer().getEmailAddress().getName()
                        : null)
                .modificabile(isAdmin)
                .seriesMasterId(seriesMasterId)
                .ricorrente(ricorrente)
                .pattern(pattern)
                .build();
    }

    /**
     * Valida i vincoli di coerenza dei parametri di ricorrenza (tipo, data fine, intervallo, giorni selezionati).
     */
    private void validaRicorrenza(PrenotazioneRequestDTO request) {
        String pattern = request.getPattern();
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("Il tipo di ricorrenza non puo essere vuoto.");
        }

        List<String> validi = List.of("daily", "weekly", "monthly");
        if (!validi.contains(pattern.toLowerCase())) {
            throw new IllegalArgumentException("Tipo di ricorrenza non valido: " + pattern);
        }

        if (request.getDataFine() == null || request.getDataFine().isEmpty()) {
            throw new IllegalArgumentException("La data di fine ricorrenza e obbligatoria.");
        }

        try {
            LocalDate startDate = LocalDate.parse(request.getStart().substring(0, 10));
            LocalDate endDate = LocalDate.parse(request.getDataFine().substring(0, 10));
            if (endDate.isBefore(startDate)) {
                log.warn("Validazione fallita: dataFine={} precedente a start={}", request.getDataFine(), request.getStart());
                throw new IllegalArgumentException(
                        "Errore: la data di fine ricorrenza deve essere successiva o uguale alla data di inizio.");
            }
        } catch (DateTimeParseException e) {
            log.warn("Validazione fallita: formato data non valido in validaRicorrenza: start={}, dataFine={}",
                    request.getStart(), request.getDataFine());
            throw new IllegalArgumentException("Errore: formato data non valido per la ricorrenza.");
        }

        if ("weekly".equalsIgnoreCase(pattern) && (request.getGiorniSettimana() == null || request.getGiorniSettimana().isEmpty())) {
            throw new IllegalArgumentException("Per la ricorrenza settimanale devi selezionare almeno un giorno.");
        }

        if (request.getIntervallo() != null && request.getIntervallo() < 1) {
            throw new IllegalArgumentException("L'intervallo deve essere almeno 1.");
        }
    }

    /**
     * Valida che gli orari di inizio e fine rispettino il formato ISO-8601 e che l'inizio sia antecedente alla fine.
     */
    private void validaOrariPrenotazione(String startStr, String endStr) {
        try {
            LocalDateTime start = LocalDateTime.parse(startStr);
            LocalDateTime end = LocalDateTime.parse(endStr);

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

    /**
     * Verifica la disponibilitÃ  della sala per la prenotazione richiesta, escludendo l'evento in modifica.
     */
    private void verificaDisponibilita(PrenotazioneRequestDTO request, String eventIdDaEscludere, Jwt jwt) {
        verificaDisponibilita(request, eventIdDaEscludere, null, jwt);
    }

    /**
     * Verifica che nessuna delle occorrenze generate dalla richiesta si sovrapponga a eventi esistenti nella sala.
     * Esclude dal confronto l'identificativo dell'evento singolo o del master della serie in corso di modifica.
     */
    private void verificaDisponibilita(PrenotazioneRequestDTO request, String eventIdDaEscludere, String seriesMasterIdDaEscludere, Jwt jwt) {
        String salaEmail = request.getSalaEmail();
        
        List<LocalDateTime[]> occorrenze = generaOccorrenze(request);
        if (occorrenze.isEmpty()) return;

        LocalDateTime startReq = LocalDateTime.parse(request.getStart().substring(0, 19));
        LocalDateTime endReq = LocalDateTime.parse(request.getEnd().substring(0, 19));
        LocalDateTime primoInizio = occorrenze.stream().map(o -> o[0]).min(LocalDateTime::compareTo).orElse(startReq);
        LocalDateTime ultimaFine = occorrenze.stream().map(o -> o[1]).max(LocalDateTime::compareTo).orElse(endReq);

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
                            if (seriesMasterIdDaEscludere.equals(e.getId())) return false;
                            if (seriesMasterIdDaEscludere.equals(e.getSeriesMasterId())) return false;
                        }
                        return true;
                    })
                    .anyMatch(e -> {
                        if (e.getStart() == null || e.getEnd() == null) return false;
                        
                        String startPulito = e.getStart().length() > 19 ? e.getStart().substring(0, 19) : e.getStart();
                        String endPulito = e.getEnd().length() > 19 ? e.getEnd().substring(0, 19) : e.getEnd();
                        
                        LocalDateTime esistenteStart = LocalDateTime.parse(startPulito);
                        LocalDateTime esistenteEnd = LocalDateTime.parse(endPulito);
                        
                        return esistenteStart.isBefore(nuovoEnd) && esistenteEnd.isAfter(nuovoStart);
                    });

            if (conflitto) {
                log.warn("Conflitto rilevato: sala {} gia occupata in data {}", salaEmail, nuovoStart.toLocalDate());
                throw new IllegalArgumentException(String.format("Errore: la sala risulta gia occupata il %s nella fascia oraria richiesta.", nuovoStart.toLocalDate().toString()));
            }
        }
    }

    /**
     * Controlla i privilegi dell'utente richiedente, sollevando AccessDeniedException se non Ã¨ un amministratore.
     */
    private void verificaAdmin(Jwt jwt) {
        if (!isAdmin(jwt)) {
            log.warn("Accesso negato: utente non admin tenta operazione riservata");
            throw new AccessDeniedException("Solo gli admin possono eseguire questa operazione");
        }
    }

    /**
     * Verifica la presenza del ruolo di amministratore all'interno dei claims del token JWT,
     * leggendo il ruolo configurato nell'ambiente (app.admin-role).
     */
    private boolean isAdmin(Jwt jwt) {
        if (jwt == null) {
            return false;
        }
        List<String> ruoli = jwt.getClaimAsStringList("roles");
        String expectedRole = (appConfig != null && appConfig.getAdminRole() != null && !appConfig.getAdminRole().isBlank())
                ? appConfig.getAdminRole()
                : "RoomBooking.Admin";
        return ruoli != null && ruoli.contains(expectedRole);
    }

    /**
     * Recupera o inizializza il ReentrantLock associato a una sala, verificando preventivamente
     * che la sala esista e appartenga all'elenco delle sale gestite da Exchange.
     */
    private ReentrantLock getLockPerSala(String salaEmail) {
        if (salaEmail == null || salaEmail.isBlank()) {
            throw new IllegalArgumentException("L'email della sala non puo essere vuota.");
        }
        String chiave = salaEmail.trim().toLowerCase();
        ReentrantLock existing = lockPerSala.get(chiave);
        if (existing != null) {
            return existing;
        }
        boolean esiste = saleService.getSale().stream()
                .anyMatch(s -> s.getEmail() != null && s.getEmail().equalsIgnoreCase(chiave));
        if (!esiste) {
            throw new IllegalArgumentException("La sala specificata non esiste: " + salaEmail);
        }
        return lockPerSala.computeIfAbsent(chiave, k -> new ReentrantLock());
    }

    /**
     * Rimuove i tag HTML dal corpo dell'evento Microsoft Graph convertendo i ritorni a capo in newline,
     * senza ricreare caratteri che potrebbero essere interpretati come codice eseguibile.
     */
    private String stripHtml(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        String testo = html.replaceAll("(?i)<br\\s*/?>", "\n")
                           .replaceAll("(?i)</p>", "\n")
                           .replaceAll("(?i)</div>", "\n");
        while (testo.matches("(?s).*<[^>]*>.*")) {
            testo = testo.replaceAll("<[^>]*>", "");
        }
        testo = testo.replace("\u00a0", " ");
        testo = testo.replace("&nbsp;", " ");
        testo = testo.replace("&amp;", "&");
        testo = testo.replace("&quot;", "\"");
        testo = testo.replace("&#39;", "'");
        testo = testo.replaceAll("[ \\t\\x0B\\f]+", " ").trim();
        return testo.isEmpty() ? null : testo;
    }

    /**
     * Calcola la lista puntuale di tutte le occorrenze [data/ora inizio, data/ora fine]
     * previste da una richiesta (evento singolo o espansione della ricorrenza daily/weekly/monthly).
     */
    private List<LocalDateTime[]> generaOccorrenze(PrenotazioneRequestDTO request) {
        List<LocalDateTime[]> occorrenze = new ArrayList<>();
        LocalDateTime start = LocalDateTime.parse(request.getStart());
        LocalDateTime end = LocalDateTime.parse(request.getEnd());

        // Evento singolo o aggiornamento su singola occorrenza: restituisce unicamente la data dell'evento
        if ("SINGOLA".equalsIgnoreCase(request.getTipoModifica())
                || request.getPattern() == null 
                || request.getPattern().isBlank()
                || request.getDataFine() == null 
                || request.getDataFine().isBlank()) {
            occorrenze.add(new LocalDateTime[]{start, end});
            return occorrenze;
        }

        LocalDate dataFine = LocalDate.parse(request.getDataFine().substring(0, 10));
        int interval = request.getIntervallo() != null ? request.getIntervallo() : 1;
        long durationMinutes = java.time.Duration.between(start, end).toMinutes();

        switch (request.getPattern().toLowerCase()) {
            case "daily":
                for (LocalDate d = start.toLocalDate(); !d.isAfter(dataFine); d = d.plusDays(interval)) {
                    occorrenze.add(new LocalDateTime[]{d.atTime(start.toLocalTime()), d.atTime(start.toLocalTime()).plusMinutes(durationMinutes)});
                }
                break;
            case "weekly":
                LocalDate weekStart = start.toLocalDate().with(java.time.DayOfWeek.MONDAY);
                for (LocalDate ws = weekStart; !ws.isAfter(dataFine); ws = ws.plusWeeks(interval)) {
                    for (String giorno : request.getGiorniSettimana()) {
                        java.time.DayOfWeek dow = java.time.DayOfWeek.valueOf(giorno.toUpperCase());
                        LocalDate d = ws.with(dow);
                        if (!d.isBefore(start.toLocalDate()) && !d.isAfter(dataFine)) {
                            occorrenze.add(new LocalDateTime[]{d.atTime(start.toLocalTime()), d.atTime(start.toLocalTime()).plusMinutes(durationMinutes)});
                        }
                    }
                }
                break;
            case "monthly":
                LocalDate baseDate = start.toLocalDate();
                int monthStep = 0;
                while (true) {
                    LocalDate d = baseDate.plusMonths((long) monthStep * interval);
                    if (d.isAfter(dataFine)) break;
                    occorrenze.add(new LocalDateTime[]{d.atTime(start.toLocalTime()), d.atTime(start.toLocalTime()).plusMinutes(durationMinutes)});
                    monthStep++;
                }
                break;
        }
        return occorrenze;
    }
}