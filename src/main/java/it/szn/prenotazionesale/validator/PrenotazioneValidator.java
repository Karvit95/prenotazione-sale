package it.szn.prenotazionesale.validator;

import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validatore stateless per le regole di business, sanitizzazione input,
 * orari e ricorrenze delle prenotazioni.
 */
@Slf4j
@Component
public class PrenotazioneValidator {

    private static final Pattern PATTERN_DATA_FILTRO = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})?$");

    private static final Pattern PATTERN_XSS_PERICOLOSO = Pattern.compile(
            "(?is)<\\s*(script|iframe|object|embed|svg|style)\\b|javascript\\s*:|on[a-z]{2,20}\\s*=");

    private static final LocalTime ORA_APERTURA = LocalTime.of(8, 0);
    private static final LocalTime ORA_CHIUSURA = LocalTime.of(20, 0);
    private static final ZoneId ZONA_ROMA = ZoneId.of("Europe/Rome");
    private static final long MAX_RANGE_GIORNI = 370L;
    private static final long MAX_DURATA_RICORRENZA_GIORNI = 365L;
    private static final int MAX_INTERVALLO = 52;
    private static final int MAX_OCCORRENZE = 366;

    /**
     * Esegue l'escape dei caratteri speciali per stringhe di filtro OData (raddoppio apici singoli).
     *
     * @param valore stringa da sottoporre ad escape
     * @return stringa sanificata per OData
     */
    public String escapeODataString(String valore) {
        if (valore == null) {
            return "";
        }
        return valore.replace("'", "''");
    }

    /**
     * Verifica che una stringa temporale rispetti il formato ISO-8601 consentito per i filtri Graph.
     *
     * @param valore        stringa data/ora
     * @param nomeParametro nome del parametro per messaggi diagnostici
     * @throws IllegalArgumentException se il formato non è conforme
     */
    public void validaParametroFiltroData(String valore, String nomeParametro) {
        if (valore == null || !PATTERN_DATA_FILTRO.matcher(valore).matches()) {
            log.warn("Parametro '{}' rifiutato per il filtro prenotazioni: valore non conforme al formato atteso",
                    nomeParametro);
            throw new IllegalArgumentException(
                    "Errore: il parametro '" + nomeParametro
                            + "' deve essere una data/ora in formato ISO-8601 valido.");
        }
    }

    /**
     * Protezione DoS (SEC-05): convalida formato, ordine cronologico e ampiezza massima
     * dell'intervallo temporale per impedire espansioni di memoria illimitate.
     *
     * @param dataInizioStr data/ora inizio intervallo
     * @param dataFineStr   data/ora fine intervallo
     * @throws IllegalArgumentException se l'intervallo non è valido o supera l'ampiezza massima (370 giorni)
     */
    public void validaIntervalloDate(String dataInizioStr, String dataFineStr) {
        validaParametroFiltroData(dataInizioStr, "dataInizio");
        validaParametroFiltroData(dataFineStr, "dataFine");

        try {
            LocalDateTime inizio = LocalDateTime.parse(dataInizioStr.substring(0, 19));
            LocalDateTime fine = LocalDateTime.parse(dataFineStr.substring(0, 19));

            if (!inizio.isBefore(fine)) {
                throw new IllegalArgumentException("Errore: la data di inizio deve essere antecedente alla data di fine.");
            }

            long giorni = java.time.Duration.between(inizio, fine).toDays();
            if (giorni > MAX_RANGE_GIORNI) {
                log.warn("Richiesta calendario respinta: ampiezza {} giorni eccede il massimo consentito ({})",
                        giorni, MAX_RANGE_GIORNI);
                throw new IllegalArgumentException(
                        "Errore: l'intervallo temporale di consultazione non puo superare " + MAX_RANGE_GIORNI + " giorni.");
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Errore: formato data non valido. Utilizzare lo standard ISO-8601.");
        }
    }

    /**
     * Valida la presenza e la sicurezza del contenuto testuale (titolo e descrizione),
     * prevenendo payload XSS o stringhe vuote composte solo da tag HTML.
     *
     * @param request payload della richiesta di prenotazione
     * @throws IllegalArgumentException in caso di payload nullo, titolo assente o tag XSS rilevati
     */
    public void validaContenutoTestuale(PrenotazioneRequestDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("La richiesta di prenotazione non puo essere nulla.");
        }
        if (request.getTitolo() == null || request.getTitolo().isBlank()) {
            throw new IllegalArgumentException("Il titolo della prenotazione e obbligatorio.");
        }
        if (PATTERN_XSS_PERICOLOSO.matcher(request.getTitolo()).find()) {
            log.warn("Rilevato payload XSS/HTML pericoloso nel titolo della prenotazione");
            throw new IllegalArgumentException("Il titolo contiene tag HTML o codice script non consentito.");
        }
        if (request.getDescrizione() != null && PATTERN_XSS_PERICOLOSO.matcher(request.getDescrizione()).find()) {
            log.warn("Rilevato payload XSS/HTML pericoloso nella descrizione della prenotazione");
            throw new IllegalArgumentException("La descrizione contiene tag HTML o codice script non consentito.");
        }
        String titoloPulito = stripHtml(request.getTitolo());
        if (titoloPulito == null || titoloPulito.isBlank()) {
            throw new IllegalArgumentException("Il titolo della prenotazione non puo contenere solo tag HTML.");
        }
    }

    /**
     * Converte una stringa temporale ISO-8601 in LocalDateTime associata al fuso di Roma (Europe/Rome).
     *
     * @param dateTimeStr stringa temporale (con o senza offset/UTC)
     * @return LocalDateTime corrispondente nel fuso di Roma
     */
    public LocalDateTime parseIsoToRomeLocalDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isBlank()) {
            throw new IllegalArgumentException("Errore: Formato data/ora non valido. Usa il formato ISO-8601.");
        }
        try {
            return LocalDateTime.parse(dateTimeStr);
        } catch (DateTimeParseException e1) {
            try {
                return OffsetDateTime.parse(dateTimeStr).atZoneSameInstant(ZONA_ROMA).toLocalDateTime();
            } catch (DateTimeParseException e2) {
                if (dateTimeStr.length() > 19) {
                    return LocalDateTime.parse(dateTimeStr.substring(0, 19));
                }
                throw e1;
            }
        }
    }

    /**
     * Valida che gli orari di inizio e fine rispettino il formato ISO-8601,
     * l'ordine cronologico, la fascia oraria aziendale (08:00 - 20:00) e non cadano nel passato.
     *
     * @param startStr data/ora inizio
     * @param endStr   data/ora fine
     * @throws IllegalArgumentException in caso di violazione delle regole orarie
     */
    public void validaOrariPrenotazione(String startStr, String endStr) {
        try {
            LocalDateTime start = parseIsoToRomeLocalDateTime(startStr);
            LocalDateTime end = parseIsoToRomeLocalDateTime(endStr);

            if (end.isBefore(start) || end.isEqual(start)) {
                log.warn("Validazione fallita: end={} prima o uguale a start={}", end, start);
                throw new IllegalArgumentException(
                        "Errore: l'orario di fine deve essere successivo all'orario di inizio.");
            }

            if (!start.toLocalDate().isEqual(end.toLocalDate())) {
                log.warn("Validazione fallita: prenotazione multi-giorno start={}, end={}", start, end);
                throw new IllegalArgumentException(
                        "Errore: l'evento deve iniziare e terminare nella stessa giornata lavorativa (08:00 - 20:00).");
            }

            if (start.toLocalTime().isBefore(ORA_APERTURA) || start.toLocalTime().isAfter(ORA_CHIUSURA)
                    || end.toLocalTime().isBefore(ORA_APERTURA) || end.toLocalTime().isAfter(ORA_CHIUSURA)) {
                log.warn("Validazione fallita: slot fuori fascia aziendale 08:00-20:00: {} -> {}", start, end);
                throw new IllegalArgumentException(
                        "Errore: le prenotazioni sono consentite esclusivamente nella fascia oraria 08:00 - 20:00.");
            }

            if (start.isBefore(LocalDateTime.now(ZONA_ROMA).minusMinutes(5))) {
                log.warn("Validazione fallita: tentativo di prenotazione nel passato: {}", start);
                throw new IllegalArgumentException(
                        "Errore: non e possibile effettuare prenotazioni per date o orari gia trascorsi.");
            }

        } catch (DateTimeParseException e) {
            log.warn("Validazione fallita: formato data non valido: start={}, end={}", startStr, endStr);
            throw new IllegalArgumentException("Errore: Formato data/ora non valido. Usa il formato ISO-8601.");
        }
    }

    /**
     * Valida i vincoli di coerenza dei parametri di ricorrenza (tipo, data fine,
     * intervallo, giorni selezionati).
     *
     * @param request payload della richiesta con parametri di ricorrenza
     * @throws IllegalArgumentException in caso di parametri incoerenti o fuori limite
     */
    public void validaRicorrenza(PrenotazioneRequestDTO request) {
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
                log.warn("Validazione fallita: dataFine={} precedente a start={}", request.getDataFine(),
                        request.getStart());
                throw new IllegalArgumentException(
                        "Errore: la data di fine ricorrenza deve essere successiva o uguale alla data di inizio.");
            }
            long giorniDifferenza = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate);
            if (giorniDifferenza > MAX_DURATA_RICORRENZA_GIORNI) {
                throw new IllegalArgumentException(
                        "Errore: una serie ricorrente puo estendersi al massimo per "
                                + MAX_DURATA_RICORRENZA_GIORNI + " giorni (1 anno).");
            }
        } catch (DateTimeParseException e) {
            log.warn("Validazione fallita: formato data non valido in validaRicorrenza: start={}, dataFine={}",
                    request.getStart(), request.getDataFine());
            throw new IllegalArgumentException("Errore: formato data non valido per la ricorrenza.");
        }

        if ("weekly".equalsIgnoreCase(pattern)) {
            if (request.getGiorniSettimana() == null || request.getGiorniSettimana().isEmpty()) {
                throw new IllegalArgumentException("Per la ricorrenza settimanale devi selezionare almeno un giorno.");
            }
            List<String> giorniValidi = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
            for (String g : request.getGiorniSettimana()) {
                if (g == null || !giorniValidi.contains(g.toUpperCase())) {
                    throw new IllegalArgumentException("Giorno della settimana non valido: " + g);
                }
            }
        }

        if (request.getIntervallo() != null
                && (request.getIntervallo() < 1 || request.getIntervallo() > MAX_INTERVALLO)) {
            throw new IllegalArgumentException(
                    "L'intervallo di ricorrenza deve essere compreso tra 1 e " + MAX_INTERVALLO + ".");
        }
    }

    /**
     * Parser lineare O(N) anti-ReDoS per la rimozione dei tag HTML dal corpo dell'evento,
     * senza backtracking esponenziale e con decodifica delle entità HTML comuni.
     *
     * @param html testo HTML sorgente
     * @return testo pulito privo di tag o null se vuoto
     */
    public String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String testo = html
                .replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n\n")
                .replaceAll("(?i)</div>", "\n");

        StringBuilder sb = new StringBuilder(testo.length());
        boolean insideTag = false;
        for (int i = 0; i < testo.length(); i++) {
            char c = testo.charAt(i);
            if (c == '<') {
                insideTag = true;
            } else if (c == '>') {
                insideTag = false;
            } else if (!insideTag) {
                sb.append(c);
            }
        }

        String pulito = sb.toString()
                .replace("\u00a0", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .trim();
        return pulito.isBlank() ? null : pulito;
    }

    /**
     * Calcola la lista puntuale di tutte le occorrenze [data/ora inizio, data/ora fine]
     * previste da una richiesta (evento singolo o espansione della ricorrenza daily/weekly/monthly).
     *
     * @param request richiesta di prenotazione
     * @return lista delle coppie inizio/fine per ciascuna occorrenza
     */
    public List<LocalDateTime[]> generaOccorrenze(PrenotazioneRequestDTO request) {
        List<LocalDateTime[]> occorrenze = new ArrayList<>();
        LocalDateTime start = parseIsoToRomeLocalDateTime(request.getStart());
        LocalDateTime end = parseIsoToRomeLocalDateTime(request.getEnd());

        // Evento singolo o aggiornamento su singola occorrenza: restituisce unicamente la data dell'evento
        if ("SINGOLA".equalsIgnoreCase(request.getTipoModifica())
                || request.getPattern() == null
                || request.getPattern().isBlank()
                || request.getDataFine() == null
                || request.getDataFine().isBlank()) {
            occorrenze.add(new LocalDateTime[] { start, end });
            return occorrenze;
        }

        LocalDate dataFine = LocalDate.parse(request.getDataFine().substring(0, 10));
        int interval = (request.getIntervallo() != null && request.getIntervallo() >= 1) ? request.getIntervallo() : 1;
        long durationMinutes = java.time.Duration.between(start, end).toMinutes();

        switch (request.getPattern().toLowerCase()) {
            case "daily":
                for (LocalDate d = start.toLocalDate(); !d.isAfter(dataFine) && occorrenze.size() < MAX_OCCORRENZE; d = d.plusDays(interval)) {
                    occorrenze.add(new LocalDateTime[] { d.atTime(start.toLocalTime()),
                            d.atTime(start.toLocalTime()).plusMinutes(durationMinutes) });
                }
                break;
            case "weekly":
                LocalDate weekStart = start.toLocalDate().with(java.time.DayOfWeek.MONDAY);
                for (LocalDate ws = weekStart; !ws.isAfter(dataFine) && occorrenze.size() < MAX_OCCORRENZE; ws = ws.plusWeeks(interval)) {
                    for (String giorno : request.getGiorniSettimana()) {
                        if (occorrenze.size() >= MAX_OCCORRENZE) {
                            break;
                        }
                        java.time.DayOfWeek dow = java.time.DayOfWeek.valueOf(giorno.toUpperCase());
                        LocalDate d = ws.with(dow);
                        if (!d.isBefore(start.toLocalDate()) && !d.isAfter(dataFine)) {
                            occorrenze.add(new LocalDateTime[] { d.atTime(start.toLocalTime()),
                                    d.atTime(start.toLocalTime()).plusMinutes(durationMinutes) });
                        }
                    }
                }
                break;
            case "monthly":
                LocalDate baseDate = start.toLocalDate();
                int monthStep = 0;
                while (occorrenze.size() < MAX_OCCORRENZE) {
                    LocalDate d = baseDate.plusMonths((long) monthStep * interval);
                    if (d.isAfter(dataFine))
                        break;
                    occorrenze.add(new LocalDateTime[] { d.atTime(start.toLocalTime()),
                            d.atTime(start.toLocalTime()).plusMinutes(durationMinutes) });
                    monthStep++;
                }
                break;
        }
        return occorrenze;
    }
}
