package it.szn.prenotazionesale.service;

import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@AllArgsConstructor
@Service
public class PrenotazioniService {

    private final GraphServiceClient graphClient;
    private final SaleService saleService;

    // Un lock per sala: serializza il blocco "verifica disponibilit\u00e0 + scrittura
    // evento"
    // cos\u00ec due richieste concorrenti sulla stessa sala non possono superare
    // entrambe il
    // controllo di disponibilit\u00e0 e creare eventi sovrapposti (TOCTOU / race
    // condition).
    //
    // NB: funziona solo con una singola istanza dell'applicazione in esecuzione (il
    // lock
    // vive in memoria, non \u00e8 condiviso tra pi\u00f9 repliche). Va bene per il deployment
    // attuale
    // a singola istanza; se in futuro si passa a pi\u00f9 repliche, questo lock non
    // basta pi\u00f9
    // e serve un lock distribuito (es. su un DB condiviso).
    private final ConcurrentHashMap<String, ReentrantLock> lockPerSala = new ConcurrentHashMap<>();

    // Executor dedicato per le chiamate Graph fatte in parallelo su pi\u00f9 sale
    // (usato da getPrenotazioniTutteSale). Dimensionato per lavoro I/O-bound
    // (thread bloccati in attesa di rete verso Graph), non CPU-bound: a differenza
    // del ForkJoinPool.commonPool() usato di default da parallelStream(), questo
    // pool non \u00e8 condiviso col resto della JVM e la sua dimensione riflette il
    // numero di sale gestite, non il numero di core della macchina.
    private final ExecutorService executorGraphParallelo = Executors.newFixedThreadPool(20);

    @PreDestroy
    public void shutdown() {
        executorGraphParallelo.shutdown();
    }

    // Whitelist rigorosa per parametri usati in un filtro OData: solo cifre, T, :, ., -, +, Z.
    // Copre sia il formato ISO UTC con 'Z' inviato dal frontend (2026-07-10T08:00:00.000Z)
    // sia il formato "naive" locale usato dalle chiamate interne (2026-07-10T08:00:00).
    // Qualsiasi altro carattere (apici, spazi, parole chiave OData come "or"/"eq") viene rifiutato.
    private static final Pattern PATTERN_DATA_FILTRO = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})?$");

    private void validaParametroFiltroData(String valore, String nomeParametro) {
        if (valore == null || !PATTERN_DATA_FILTRO.matcher(valore).matches()) {
            log.warn("Parametro '{}' rifiutato per il filtro prenotazioni: valore non conforme al formato atteso", nomeParametro);
            throw new IllegalArgumentException(
                    "Errore: il parametro '" + nomeParametro + "' deve essere una data/ora in formato ISO-8601 valido.");
        }
    }

    public List<PrenotazioneDTO> getPrenotazioni(String salaEmail, String dataInizio, String dataFine, Jwt jwt) {
        log.info("Richiesta prenotazioni per sala {} dal {} al {}", salaEmail, dataInizio, dataFine);

        validaParametroFiltroData(dataInizio, "dataInizio");
        validaParametroFiltroData(dataFine, "dataFine");

        // Usiamo calendarView invece di calendar/events perch\u00e9 calendarView
        // espande automaticamente le occorrenze degli eventi ricorrenti nel
        // periodo richiesto. Con calendar/events riceviamo solo l'evento master
        // (la prima occorrenza), non tutte le istanze della serie.
        var events = graphClient.users().byUserId(salaEmail).calendarView().get(req -> {
            req.queryParameters.startDateTime = dataInizio;
            req.queryParameters.endDateTime = dataFine;
            req.headers.add("Prefer", "outlook.timezone=\"Europe/Rome\"");
        }).getValue();

        log.debug("Trovati {} eventi per la sala {}", events.size(), salaEmail);

        return events.stream().map(event -> mapToDTO(event, salaEmail, jwt)).collect(Collectors.toList());
    }

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

    public PrenotazioneDTO creaPrenotazione(PrenotazioneRequestDTO request, Jwt jwt) {
        log.info("Creazione prenotazione per sala {} - titolo: {}", request.getSalaEmail(), request.getTitolo());
        verificaAdmin(jwt);
        validaOrariPrenotazione(request.getStart(), request.getEnd());

        // Se ricorrente, valida anche i campi specifici
        if (request.getPattern() != null) {
            validaRicorrenza(request);
        }

        ReentrantLock lock = getLockPerSala(request.getSalaEmail());
        lock.lock();

        try {
            verificaDisponibilita(request, null, jwt);
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

        // Se la sala \u00e8 cambiata, cancella dalla vecchia e crea sulla nuova
        if (salaOriginale != null && !salaOriginale.equals(request.getSalaEmail())) {
            log.info("Sala cambiata da {} a {}: creo il nuovo evento prima di cancellare il vecchio", salaOriginale,
                    request.getSalaEmail());

            boolean modificaSerie = "SERIE".equals(request.getTipoModifica());
            boolean haSeriesMaster = request.getSeriesMasterId() != null && !request.getSeriesMasterId().isBlank();

            PrenotazioneDTO nuovaPrenotazione;

            if (modificaSerie && haSeriesMaster) {
                // Cambio sala su TUTTA LA SERIE:
                // Recupera il series master originale dalla vecchia sala per copiare
                // i dati corretti (data di inizio originale della serie, recurrence pattern,
                // range, etc.) \u2014 altrimenti verrebbe usata la data dell'occorrenza cliccata
                // nella nuova serie, causando dati errati.
                log.info("Modifica serie su cambio sala: recupero master originale ID {} dalla sala {}",
                        request.getSeriesMasterId(), salaOriginale);

                var masterOriginale = graphClient.users().byUserId(salaOriginale).events()
                        .byEventId(request.getSeriesMasterId()).get();

                // Costruisci il nuovo evento copiando i dati del master originale
                var nuovoEvento = new Event();
                nuovoEvento.setSubject(request.getTitolo());

                if (request.getDescrizione() != null) {
                    var body = new ItemBody();
                    body.setContent(request.getDescrizione());
                    body.setContentType(BodyType.Text);
                    nuovoEvento.setBody(body);
                }

                // Usa la data/ora del master originale (non dell'occorrenza cliccata)
                var start = new DateTimeTimeZone();
                start.setDateTime(masterOriginale.getStart().getDateTime());
                start.setTimeZone("Europe/Rome");
                nuovoEvento.setStart(start);

                var end = new DateTimeTimeZone();
                end.setDateTime(masterOriginale.getEnd().getDateTime());
                end.setTimeZone("Europe/Rome");
                nuovoEvento.setEnd(end);

                // Copia la recurrence dal master originale (pattern + range)
                // IMPORTANTE: non possiamo usare masterOriginale.getRecurrence() direttamente
                // perch\u00e9 Graph API richiede oggetti costruiti esplicitamente per la creazione
                // di nuovi eventi (setRecurrence da un oggetto esistente causa errore
                // "Missing parameters: Pattern").
                var recurrenceOriginale = masterOriginale.getRecurrence();
                if (recurrenceOriginale != null && recurrenceOriginale.getPattern() != null) {
                    var nuovaRecurrence = new PatternedRecurrence();

                    // Copia il pattern
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

                    // Copia il range
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
                // Cambio sala su SINGOLA OCCORRENZA:
                // Crea un singolo evento (NO ricorrenza) nella nuova sala usando la data
                // dell'occorrenza cliccata. Il frontend si \u00e8 gi\u00e0 assicurato di non inviare
                // pattern in questo caso (isSingleOccurrenceWithRoomChange).
                nuovaPrenotazione = creaPrenotazione(request, jwt);
            } else {
                // Evento singolo (non ricorrente): comportamento normale
                nuovaPrenotazione = creaPrenotazione(request, jwt);
            }

            try {
                // Stessa logica delle altre operazioni sulle serie: se la modifica riguarda
                // tutta la serie e abbiamo il seriesMasterId, va cancellato quello \u2014 non
                // l'ID dell'occorrenza cliccata, altrimenti resterebbero attive le altre
                // occorrenze della vecchia serie sulla sala originale.
                String idDaCancellare = (modificaSerie && haSeriesMaster)
                                ? request.getSeriesMasterId()
                                : eventId;

                cancellaPrenotazione(idDaCancellare, salaOriginale, jwt);
            } catch (Exception e) {
                log.error(
                        "Prenotazione spostata con ID nuovo {} ma impossibile cancellare l'evento originale {} sulla sala {}: rimane una prenotazione duplicata da rimuovere manualmente",
                        nuovaPrenotazione.getId(), eventId, salaOriginale, e);
                throw new IllegalStateException("La prenotazione \u00e8 stata creata sulla nuova sala (ID "
                        + nuovaPrenotazione.getId()
                        + "), ma non \u00e8 stato possibile rimuovere quella originale sulla sala precedente (ID " + eventId
                        + "). Contatta l'amministratore IT per la cancellazione manuale della vecchia prenotazione.",
                        e);
            }

            return nuovaPrenotazione;
        }

        ReentrantLock lock = getLockPerSala(request.getSalaEmail());
        lock.lock();

        try {
            verificaDisponibilita(request, eventId, jwt);

            // Determina se modificare la singola occorrenza o l'intera serie
            String tipoModifica = request.getTipoModifica();
            boolean modificaSerie = "SERIE".equals(tipoModifica);

        if (modificaSerie) {
            // PATCH sul series master: in Graph, l'effetto del PATCH dipende da QUALE ID
            // usi, non da un flag. Se abbiamo l'ID del series master (evento ricorrente),
            // dobbiamo usare quello \u2014 l'ID dell'occorrenza cliccata toccherebbe solo
            // quella singola data, vanificando la scelta "modifica tutta la serie".
            String idSuCuiOperare = (request.getSeriesMasterId() != null && !request.getSeriesMasterId().isBlank())
                    ? request.getSeriesMasterId()
                    : eventId; // evento non ricorrente: eventId \u00e8 gi\u00e0 l'ID corretto

            // Non usiamo buildEvent() perch\u00e9 includerebbe il campo recurrence,
            // che su Graph SOSTITUIREBBE (non unirebbe) la definizione della
            // ricorrenza originale. Invece costruiamo un corpo che aggiorni solo
            // i campi base (titolo, descrizione, start/end) SENZA il campo
            // recurrence: in questo modo Graph preserva la ricorrenza originale
            // dell'evento master.
            var event = new Event();
            event.setSubject(request.getTitolo());

            if (request.getDescrizione() != null) {
                var body = new ItemBody();
                body.setContent(request.getDescrizione());
                body.setContentType(BodyType.Text);
                event.setBody(body);
            }

            // IMPORTANTE: quando si modifica l'INTERA SERIE, la request contiene la data
            // dell'occorrenza su cui l'utente ha cliccato, NON la data originale del series
            // master. Se usassimo request.getStart() cos\u00ec com'\u00e8, il PATCH cambierebbe anche
            // la data di inizio del master, causando la re-evaluazione di tutte le eccezioni
            // (occorrenze con data modificata individualmente) da parte di Graph API.
            //
            // Per evitare questo bug, recuperiamo l'evento master corrente e preserviamo
            // la sua data originale (AAAA-MM-GG), applicando solo il nuovo orario richiesto.
            String nuovoStartDateTime;
            String nuovoEndDateTime;

            try {
                // Recupera l'evento master per ottenere la data originale
                var existingMaster = graphClient.users().byUserId(request.getSalaEmail()).events()
                        .byEventId(idSuCuiOperare).get();

                String dataOriginaleMaster = existingMaster.getStart().getDateTime().substring(0, 10);

                // Calcola la durata dalla request
                LocalDateTime reqStart = LocalDateTime.parse(request.getStart().substring(0, 19));
                LocalDateTime reqEnd = LocalDateTime.parse(request.getEnd().substring(0, 19));
                long durataMinuti = java.time.Duration.between(reqStart, reqEnd).toMinutes();

                // Nuovo start: data originale del master + orario dalla request
                String nuovoOrario = request.getStart().substring(11, 19); // HH:mm:ss
                nuovoStartDateTime = dataOriginaleMaster + "T" + nuovoOrario;

                // Nuovo end: stessa data originale + (orario dalla request + durata)
                LocalDateTime nuovoEndLDT = LocalDateTime.parse(dataOriginaleMaster + "T" + nuovoOrario)
                        .plusMinutes(durataMinuti);
                nuovoEndDateTime = nuovoEndLDT.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

                log.debug("Modifica SERIE: preservata data originale master {} con nuovo orario {} -> start={}, end={}",
                        dataOriginaleMaster, nuovoOrario, nuovoStartDateTime, nuovoEndDateTime);
            } catch (Exception e) {
                // Fallback: se non riusciamo a leggere il master, usiamo i valori originali
                // (comportamento pre-bugfix, potrebbe comunque causare il bug ma non blocca l'operazione)
                log.warn("Impossibile recuperare il master per preservare la data originale, uso i valori della request: {}",
                        e.getMessage());
                nuovoStartDateTime = request.getStart();
                nuovoEndDateTime = request.getEnd();
            }

            var start = new DateTimeTimeZone();
            start.setDateTime(nuovoStartDateTime);
            start.setTimeZone("Europe/Rome");
            event.setStart(start);

            var end = new DateTimeTimeZone();
            end.setDateTime(nuovoEndDateTime);
            end.setTimeZone("Europe/Rome");
            event.setEnd(end);

            var updatedEvent = graphClient.users().byUserId(request.getSalaEmail()).events()
                    .byEventId(idSuCuiOperare).patch(event);
            log.info("Prenotazione (serie, ID {}) modificata con successo", idSuCuiOperare);
            return mapToDTO(updatedEvent, request.getSalaEmail(), jwt);
            } else {
                // PATCH sulla singola occorrenza: costruiamo un corpo che aggiorni solo
                // i campi base (start/end/titolo/descrizione) SENZA la ricorrenza
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

        } finally {

            lock.unlock();

        }
    }

    public void cancellaPrenotazione(String eventId, String salaEmail, Jwt jwt) {
        cancellaPrenotazione(eventId, salaEmail, "SERIE", null, jwt);
    }

    public void cancellaPrenotazione(String eventId, String salaEmail, String tipoCancellazione, String seriesMasterId, Jwt jwt) {
        log.info("Cancellazione prenotazione ID {} per sala {} (tipo: {}, seriesMasterId: {})", eventId, salaEmail,
                tipoCancellazione, seriesMasterId);
        verificaAdmin(jwt);

        ReentrantLock lock = getLockPerSala(salaEmail);
        lock.lock();

        try {
            // In Graph, cancellare un'occorrenza singola o l'intera serie usa la STESSA
            // operazione (DELETE): cambia solo l'ID target. Passare l'ID del series master
            // cancella tutta la serie; passare l'ID di un'occorrenza cancella solo quella data.
            // Non serve (e non \u00e8 affidabile) un PATCH con isCancelled=true.
            String idSuCuiOperare = "SERIE".equals(tipoCancellazione) && seriesMasterId != null && !seriesMasterId.isBlank()
                    ? seriesMasterId
                    : eventId; // occorrenza singola, oppure evento non ricorrente

            graphClient.users().byUserId(salaEmail).events().byEventId(idSuCuiOperare).delete();
            log.info("Prenotazione {} cancellata dalla sala {} (tipo richiesto: {})", idSuCuiOperare, salaEmail,
                    tipoCancellazione);

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

        // Aggiungi ricorrenza se richiesta
        if (request.getPattern() != null && !request.getPattern().isEmpty()) {
            PatternedRecurrence recurrence = new PatternedRecurrence();

            // Estrazione unica della data di inizio per evitare conflitti o duplicazioni di variabili
            String startDateStr = request.getStart().length() > 10
                    ? request.getStart().substring(0, 10)
                    : request.getStart();
            LocalDate dataInizio = LocalDate.parse(startDateStr);

            // 1. Configurazione del Pattern
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
                                // La SDK Microsoft Graph richiede i giorni rigorosamente in minuscolo (es: "monday")
                                .map(giorno -> DayOfWeek.forValue(giorno.toLowerCase()))
                                .collect(Collectors.toList()));
                    } else {
                        // Fallback di sicurezza: se la lista \u00e8 vuota usa il giorno della data di inizio
                        String dayName = dataInizio.getDayOfWeek().name().toLowerCase();
                        pattern.setDaysOfWeek(List.of(DayOfWeek.forValue(dayName)));
                    }
                    break;
                case "monthly":
                    pattern.setType(RecurrencePatternType.AbsoluteMonthly);
                    // OBBLIGATORIO per Microsoft Graph: fissa il giorno del mese (es. il 15 di ogni mese)
                    pattern.setDayOfMonth(dataInizio.getDayOfMonth());
                    break;
                default:
                    throw new IllegalArgumentException("Tipo di ricorrenza non supportato: " + request.getPattern());
            }

            recurrence.setPattern(pattern);

            // 2. Configurazione del Range
            RecurrenceRange range = new RecurrenceRange();
            range.setType(RecurrenceRangeType.EndDate);

            if (request.getDataFine() != null) {
                String dataFineStr = request.getDataFine().length() > 10
                        ? request.getDataFine().substring(0, 10)
                        : request.getDataFine();
                range.setEndDate(LocalDate.parse(dataFineStr));
            } else {
                // Fallback: fine dopo 365 giorni
                range.setNumberOfOccurrences(365);
                range.setType(RecurrenceRangeType.Numbered);
            }

            // Riutilizzo pulito della variabile dataInizio estratta in precedenza
            range.setStartDate(dataInizio);

            recurrence.setRange(range);

            event.setRecurrence(recurrence);
            log.debug("Ricorrenza aggiunta: pattern={}, intervallo={}, dataFine={}",
                    request.getPattern(), request.getIntervallo(), request.getDataFine());
        }

        return event;
    }

    private PrenotazioneDTO mapToDTO(Event event, String salaEmail, Jwt jwt) {
        boolean isAdmin = isAdmin(jwt);

        // Estrai informazioni ricorrenza
        String seriesMasterId = null;
        boolean ricorrente = false;
        String pattern = null;

        if (event.getSeriesMasterId() != null && !event.getSeriesMasterId().isEmpty()) {
            seriesMasterId = event.getSeriesMasterId();
            ricorrente = true;
        }
        // L'evento master stesso ha un recurrence ma non seriesMasterId
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

        // Microsoft Graph restituisce il corpo dell'evento in formato HTML
        // Esempio: "<html><head>...<body>Testo</body></html>"
        // Per visualizzarlo correttamente nel frontend (textarea), estraiamo solo il testo puro.
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

        // La data di fine ricorrenza deve essere >= data di inizio
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

    private void validaOrariPrenotazione(String startStr, String endStr) {
        try {
            LocalDateTime start = LocalDateTime.parse(startStr);
            LocalDateTime end = LocalDateTime.parse(endStr);

            // 1. La fine non puo essere prima (o uguale) all'inizio
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

    private void verificaDisponibilita(PrenotazioneRequestDTO request, String eventIdDaEscludere, Jwt jwt) {
        String salaEmail = request.getSalaEmail();
        
        // 1. Genera tutte le occorrenze previste per la prenotazione
        List<LocalDateTime[]> occorrenze = generaOccorrenze(request);
        if (occorrenze.isEmpty()) return;

        // 2. Finestra temporale totale per la chiamata Graph API
        LocalDateTime primoInizio = occorrenze.get(0)[0];
        LocalDateTime ultimaFine = occorrenze.get(occorrenze.size() - 1)[1];

        DateTimeFormatter formatoFiltro = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        String filtroInizio = primoInizio.toLocalDate().atStartOfDay().format(formatoFiltro);
        String filtroFine = ultimaFine.toLocalDate().plusDays(1).atStartOfDay().format(formatoFiltro);

        // 3. Recupera gli eventi nel maxi-periodo
        List<PrenotazioneDTO> eventiEsistenti = getPrenotazioni(salaEmail, filtroInizio, filtroFine, jwt);

        // 4. Verifica sovrapposizioni per singola occorrenza
        for (LocalDateTime[] occorrenza : occorrenze) {
            LocalDateTime nuovoStart = occorrenza[0];
            LocalDateTime nuovoEnd = occorrenza[1];

            boolean conflitto = eventiEsistenti.stream()
                    .filter(e -> eventIdDaEscludere == null || !e.getId().equals(eventIdDaEscludere))
                    .anyMatch(e -> {
                        if (e.getStart() == null || e.getEnd() == null) return false;
                        
                        // Pulizia stringa per parse safe
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

    /**
     * Rimuove i tag HTML da una stringa, estraendo solo il testo puro.
     * Gestisce anche i casi in cui il contenuto sia solo testo senza tag.
     */
    private String stripHtml(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        String testo = html.replaceAll("<[^>]*>", "");
        testo = testo.replace("\u00a0", " ");
        testo = testo.replace("&", "&");
        testo = testo.replace("<", "<");
        testo = testo.replace(">", ">");
        testo = testo.replace("&#39;", "'");
        testo = testo.replaceAll("\\s+", " ").trim();
        return testo;
    }
    
    private List<LocalDateTime[]> generaOccorrenze(PrenotazioneRequestDTO request) {
        List<LocalDateTime[]> occorrenze = new ArrayList<>();
        LocalDateTime start = LocalDateTime.parse(request.getStart());
        LocalDateTime end = LocalDateTime.parse(request.getEnd());
        
        // Se \u00e8 un evento singolo, restituisce solo l'unica occorrenza
        if (request.getPattern() == null || request.getPattern().isBlank()) {
            occorrenze.add(new LocalDateTime[]{start, end});
            return occorrenze;
        }

        // Calcolo ricorrenze
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
                // Usa java.time.DayOfWeek con FQDN per evitare conflitti con l'enum di Graph
                LocalDate weekStart = start.toLocalDate().with(java.time.DayOfWeek.MONDAY);
                for (LocalDate ws = weekStart; !ws.isAfter(dataFine); ws = ws.plusWeeks(interval)) {
                    for (String giorno : request.getGiorniSettimana()) {
                        java.time.DayOfWeek dow = java.time.DayOfWeek.valueOf(giorno.toUpperCase());
                        LocalDate d = ws.with(dow);
                        // Filtra i giorni precedenti alla data di inizio effettiva o successivi alla fine
                        if (!d.isBefore(start.toLocalDate()) && !d.isAfter(dataFine)) {
                            occorrenze.add(new LocalDateTime[]{d.atTime(start.toLocalTime()), d.atTime(start.toLocalTime()).plusMinutes(durationMinutes)});
                        }
                    }
                }
                break;
            case "monthly":
                for (LocalDate d = start.toLocalDate(); !d.isAfter(dataFine); d = d.plusMonths(interval)) {
                    occorrenze.add(new LocalDateTime[]{d.atTime(start.toLocalTime()), d.atTime(start.toLocalTime()).plusMinutes(durationMinutes)});
                }
                break;
        }
        return occorrenze;
    }
}