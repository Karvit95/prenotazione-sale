package it.szn.prenotazionesale.mapper;

import com.microsoft.graph.models.*;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.validator.PrenotazioneValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mapper stateless per la conversione bidirezionale tra i DTO applicativi
 * (PrenotazioneRequestDTO, PrenotazioneDTO) e i modelli Microsoft Graph (Event).
 */
@Slf4j
@Component
public class PrenotazioneMapper {

    private final PrenotazioneValidator validator;

    public PrenotazioneMapper(PrenotazioneValidator validator) {
        this.validator = validator;
    }

    /**
     * Imposta il soggetto e il corpo testuale sanificato dell'evento Graph a partire dai dati della richiesta.
     * Se la descrizione nella richiesta è null, il corpo dell'evento non viene impostato.
     *
     * @param event   oggetto Event Microsoft Graph da valorizzare
     * @param request payload con i dati della prenotazione
     */
    public void impostaTitoloEDescrizione(Event event, PrenotazioneRequestDTO request) {
        event.setSubject(validator.stripHtml(request.getTitolo()));

        if (request.getDescrizione() != null) {
            var body = new ItemBody();
            String descSanificata = validator.stripHtml(request.getDescrizione());
            body.setContent(descSanificata != null ? descSanificata : "");
            body.setContentType(BodyType.Text);
            event.setBody(body);
        }
    }

    /**
     * Costruisce l'oggetto Event di Microsoft Graph popolando metadati, intervalli orari
     * (con fuso orario fissato a Europe/Rome) ed eventuale regola di ricorrenza (Daily, Weekly, Monthly).
     *
     * @param request dati della richiesta di prenotazione
     * @return oggetto Event pronto per la creazione su Microsoft Graph
     */
    public Event buildEvent(PrenotazioneRequestDTO request) {
        log.debug("Costruzione evento per sala {}: start={}, end={}", request.getSalaEmail(), request.getStart(),
                request.getEnd());
        var event = new Event();
        impostaTitoloEDescrizione(event, request);

        DateTimeFormatter fmtIso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        LocalDateTime parsedStart = validator.parseIsoToRomeLocalDateTime(request.getStart());
        LocalDateTime parsedEnd = validator.parseIsoToRomeLocalDateTime(request.getEnd());

        var start = new DateTimeTimeZone();
        start.setDateTime(parsedStart.format(fmtIso));
        start.setTimeZone("Europe/Rome");
        event.setStart(start);

        var end = new DateTimeTimeZone();
        end.setDateTime(parsedEnd.format(fmtIso));
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
     * Estrae identificativi di serie, ripulisce la descrizione da tag HTML e imposta il flag modificabile.
     *
     * @param event        evento Microsoft Graph
     * @param salaEmail    email della casella di sala
     * @param modificabile true se l'utente possiede i permessi per modificare/cancellare la prenotazione
     * @return DTO rappresentativo della prenotazione
     */
    public PrenotazioneDTO mapToDTO(Event event, String salaEmail, boolean modificabile) {
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
                    case Daily:
                        pattern = "daily";
                        break;
                    case Weekly:
                        pattern = "weekly";
                        break;
                    case AbsoluteMonthly:
                        pattern = "monthly";
                        break;
                    default:
                        pattern = patternType.toString().toLowerCase();
                }
            }
        }

        String descrizionePulita = null;
        if (event.getBody() != null && event.getBody().getContent() != null) {
            descrizionePulita = validator.stripHtml(event.getBody().getContent());
        }

        return PrenotazioneDTO.builder().id(event.getId())
                .salaId(salaEmail != null ? salaEmail.trim().toLowerCase() : null).salaEmail(salaEmail)
                .titolo(event.getSubject()).descrizione(descrizionePulita)
                .start(event.getStart() != null ? event.getStart().getDateTime() : null)
                .end(event.getEnd() != null ? event.getEnd().getDateTime() : null)
                .organizzatoreNome(event.getOrganizer() != null && event.getOrganizer().getEmailAddress() != null
                        ? event.getOrganizer().getEmailAddress().getName()
                        : null)
                .modificabile(modificabile)
                .seriesMasterId(seriesMasterId)
                .ricorrente(ricorrente)
                .pattern(pattern)
                .build();
    }
}

