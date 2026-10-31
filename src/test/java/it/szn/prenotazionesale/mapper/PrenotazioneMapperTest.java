package it.szn.prenotazionesale.mapper;

import com.microsoft.graph.models.*;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.validator.PrenotazioneValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Test isolati per PrenotazioneMapper.
 * Verifica la corretta costruzione degli oggetti Event Graph da PrenotazioneRequestDTO
 * e la mappatura bidirezionale verso PrenotazioneDTO, senza dipendenze o mock di rete.
 */
public class PrenotazioneMapperTest {

    private PrenotazioneValidator validator;
    private PrenotazioneMapper mapper;

    @BeforeEach
    void setUp() {
        validator = new PrenotazioneValidator();
        mapper = new PrenotazioneMapper(validator);
    }

    @Nested
    @DisplayName("1. Costruzione Evento Singolo (buildEvent)")
    class BuildSingleEventTests {

        @Test
        @DisplayName("Dovrebbe costruire un evento singolo con subject, body, date e timezone Europe/Rome")
        void testBuildEvent_SingleEvent_Success() {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala-dohrn@szn.it")
                    .titolo("Meeting Progetto")
                    .descrizione("Discussione milestone Q1")
                    .start("2026-11-10T10:00:00")
                    .end("2026-11-10T11:00:00")
                    .build();

            Event event = mapper.buildEvent(request);

            assertNotNull(event);
            assertEquals("Meeting Progetto", event.getSubject());
            assertNotNull(event.getBody());
            assertEquals("Discussione milestone Q1", event.getBody().getContent());
            assertEquals(BodyType.Text, event.getBody().getContentType());

            assertNotNull(event.getStart());
            assertEquals("2026-11-10T10:00:00", event.getStart().getDateTime());
            assertEquals("Europe/Rome", event.getStart().getTimeZone());

            assertNotNull(event.getEnd());
            assertEquals("2026-11-10T11:00:00", event.getEnd().getDateTime());
            assertEquals("Europe/Rome", event.getEnd().getTimeZone());

            assertNull(event.getRecurrence());
        }

        @Test
        @DisplayName("Se la descrizione è null, il body dell'evento non deve essere impostato")
        void testBuildEvent_NullDescription_BodyNotSet() {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala-dohrn@szn.it")
                    .titolo("Meeting Senza Descrizione")
                    .descrizione(null)
                    .start("2026-11-10T10:00:00")
                    .end("2026-11-10T11:00:00")
                    .build();

            Event event = mapper.buildEvent(request);

            assertNotNull(event);
            assertEquals("Meeting Senza Descrizione", event.getSubject());
            assertNull(event.getBody(), "Il body non deve essere impostato se descrizione è null");
        }

        @Test
        @DisplayName("Dovrebbe sanificare titolo e descrizione rimuovendo codice HTML e script")
        void testBuildEvent_HtmlSanitization() {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala-dohrn@szn.it")
                    .titolo("<b>Titolo con Tag</b>")
                    .descrizione("<p>Paragrafo con <script>alert(1)</script>testo visibile</p>")
                    .start("2026-11-10T10:00:00")
                    .end("2026-11-10T11:00:00")
                    .build();

            Event event = mapper.buildEvent(request);

            assertNotNull(event);
            assertEquals("Titolo con Tag", event.getSubject());
            assertNotNull(event.getBody());
            assertEquals("Paragrafo con testo visibile", event.getBody().getContent());
        }

        @Test
        @DisplayName("Dovrebbe lanciare NullPointerException se la request è null")
        void testBuildEvent_NullRequest_ThrowsNpe() {
            assertThrows(NullPointerException.class, () -> mapper.buildEvent(null));
        }
    }

    @Nested
    @DisplayName("2. Costruzione Eventi Ricorrenti (buildEvent)")
    class BuildRecurringEventTests {

        @Test
        @DisplayName("Dovrebbe costruire evento ricorrente Daily con intervallo e data fine")
        void testBuildEvent_DailyRecurrence() {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala-dohrn@szn.it")
                    .titolo("Daily Standup")
                    .start("2026-11-10T09:00:00")
                    .end("2026-11-10T09:30:00")
                    .pattern("daily")
                    .intervallo(2)
                    .dataFine("2026-11-20T09:30:00")
                    .build();

            Event event = mapper.buildEvent(request);

            assertNotNull(event);
            assertNotNull(event.getRecurrence());
            assertEquals(RecurrencePatternType.Daily, event.getRecurrence().getPattern().getType());
            assertEquals(2, event.getRecurrence().getPattern().getInterval());

            assertNotNull(event.getRecurrence().getRange());
            assertEquals(RecurrenceRangeType.EndDate, event.getRecurrence().getRange().getType());
            assertEquals(LocalDate.parse("2026-11-10"), event.getRecurrence().getRange().getStartDate());
            assertEquals(LocalDate.parse("2026-11-20"), event.getRecurrence().getRange().getEndDate());
        }

        @Test
        @DisplayName("Dovrebbe costruire evento ricorrente Weekly con giorni specificati")
        void testBuildEvent_WeeklyRecurrence() {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala-dohrn@szn.it")
                    .titolo("All-Hands Settimanale")
                    .start("2026-11-09T14:00:00")
                    .end("2026-11-09T15:00:00")
                    .pattern("weekly")
                    .intervallo(1)
                    .giorniSettimana(List.of("MONDAY", "WEDNESDAY"))
                    .dataFine("2026-12-31T15:00:00")
                    .build();

            Event event = mapper.buildEvent(request);

            assertNotNull(event);
            assertNotNull(event.getRecurrence());
            assertEquals(RecurrencePatternType.Weekly, event.getRecurrence().getPattern().getType());
            assertEquals(1, event.getRecurrence().getPattern().getInterval());
            assertThat(event.getRecurrence().getPattern().getDaysOfWeek())
                    .containsExactlyInAnyOrder(DayOfWeek.Monday, DayOfWeek.Wednesday);
        }

        @Test
        @DisplayName("Dovrebbe costruire evento ricorrente Monthly con giorno del mese")
        void testBuildEvent_MonthlyRecurrence() {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala-dohrn@szn.it")
                    .titolo("Consiglio Mensile")
                    .start("2026-11-15T10:00:00")
                    .end("2026-11-15T12:00:00")
                    .pattern("monthly")
                    .intervallo(1)
                    .dataFine("2027-05-15T12:00:00")
                    .build();

            Event event = mapper.buildEvent(request);

            assertNotNull(event);
            assertNotNull(event.getRecurrence());
            assertEquals(RecurrencePatternType.AbsoluteMonthly, event.getRecurrence().getPattern().getType());
            assertEquals(15, event.getRecurrence().getPattern().getDayOfMonth());
        }

        @Test
        @DisplayName("Se dataFine è null, imposta ricorrenza Numbered con 365 occorrenze")
        void testBuildEvent_NoDataFine_NumberedOccurrences() {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala-dohrn@szn.it")
                    .titolo("Meeting Senza Data Fine")
                    .start("2026-11-10T10:00:00")
                    .end("2026-11-10T11:00:00")
                    .pattern("daily")
                    .intervallo(1)
                    .dataFine(null)
                    .build();

            Event event = mapper.buildEvent(request);

            assertNotNull(event);
            assertNotNull(event.getRecurrence());
            assertEquals(RecurrenceRangeType.Numbered, event.getRecurrence().getRange().getType());
            assertEquals(365, event.getRecurrence().getRange().getNumberOfOccurrences());
        }
    }

    @Nested
    @DisplayName("3. Mappatura verso DTO (mapToDTO)")
    class MapToDTOTests {

        @Test
        @DisplayName("Dovrebbe mappare un evento completo con modificabile true/false")
        void testMapToDTO_CompleteEvent() {
            Event event = new Event();
            event.setId("evt-123");
            event.setSubject("Riunione Strategica");

            ItemBody body = new ItemBody();
            body.setContent("Note di progetto");
            body.setContentType(BodyType.Text);
            event.setBody(body);

            DateTimeTimeZone start = new DateTimeTimeZone();
            start.setDateTime("2026-11-10T10:00:00");
            event.setStart(start);

            DateTimeTimeZone end = new DateTimeTimeZone();
            end.setDateTime("2026-11-10T11:00:00");
            event.setEnd(end);

            Recipient organizer = new Recipient();
            EmailAddress emailAddress = new EmailAddress();
            emailAddress.setName("Mario Rossi");
            emailAddress.setAddress("mario.rossi@szn.it");
            organizer.setEmailAddress(emailAddress);
            event.setOrganizer(organizer);

            // Modificabile = true
            PrenotazioneDTO dtoAdmin = mapper.mapToDTO(event, "sala-dohrn@szn.it", true);
            assertNotNull(dtoAdmin);
            assertEquals("evt-123", dtoAdmin.getId());
            assertEquals("sala-dohrn@szn.it", dtoAdmin.getSalaId());
            assertEquals("sala-dohrn@szn.it", dtoAdmin.getSalaEmail());
            assertEquals("Riunione Strategica", dtoAdmin.getTitolo());
            assertEquals("Note di progetto", dtoAdmin.getDescrizione());
            assertEquals("2026-11-10T10:00:00", dtoAdmin.getStart());
            assertEquals("2026-11-10T11:00:00", dtoAdmin.getEnd());
            assertEquals("Mario Rossi", dtoAdmin.getOrganizzatoreNome());
            assertTrue(dtoAdmin.isModificabile());
            assertFalse(dtoAdmin.isRicorrente());
            assertNull(dtoAdmin.getSeriesMasterId());

            // Modificabile = false
            PrenotazioneDTO dtoUser = mapper.mapToDTO(event, "sala-dohrn@szn.it", false);
            assertFalse(dtoUser.isModificabile());
        }

        @Test
        @DisplayName("Dovrebbe mappare evento ricorrente con seriesMasterId e pattern")
        void testMapToDTO_RecurringEvent() {
            Event event = new Event();
            event.setId("occurrence-456");
            event.setSeriesMasterId("master-789");
            event.setSubject("Daily Standup");

            PatternedRecurrence recurrence = new PatternedRecurrence();
            RecurrencePattern pattern = new RecurrencePattern();
            pattern.setType(RecurrencePatternType.Weekly);
            recurrence.setPattern(pattern);
            event.setRecurrence(recurrence);

            PrenotazioneDTO dto = mapper.mapToDTO(event, "sala-dohrn@szn.it", true);

            assertNotNull(dto);
            assertTrue(dto.isRicorrente());
            assertEquals("master-789", dto.getSeriesMasterId());
            assertEquals("weekly", dto.getPattern());
        }

        @Test
        @DisplayName("Dovrebbe gestire in modo robusto eventi con campi null")
        void testMapToDTO_NullFields() {
            Event emptyEvent = new Event();
            emptyEvent.setId("empty-1");

            PrenotazioneDTO dto = mapper.mapToDTO(emptyEvent, null, false);

            assertNotNull(dto);
            assertEquals("empty-1", dto.getId());
            assertNull(dto.getSalaId());
            assertNull(dto.getSalaEmail());
            assertNull(dto.getTitolo());
            assertNull(dto.getDescrizione());
            assertNull(dto.getStart());
            assertNull(dto.getEnd());
            assertNull(dto.getOrganizzatoreNome());
            assertFalse(dto.isModificabile());
            assertFalse(dto.isRicorrente());
            assertNull(dto.getSeriesMasterId());
            assertNull(dto.getPattern());
        }

        @Test
        @DisplayName("Dovrebbe lanciare NullPointerException se l'evento è null")
        void testMapToDTO_NullEvent_ThrowsNpe() {
            assertThrows(NullPointerException.class, () -> mapper.mapToDTO(null, "sala-dohrn@szn.it", true));
        }
    }

    @Nested
    @DisplayName("4. Impostazione Titolo e Descrizione (impostaTitoloEDescrizione)")
    class ImpostaTitoloEDescrizioneTests {

        @Test
        @DisplayName("Dovrebbe valorizzare subject e body con sanitizzazione HTML")
        void testImpostaTitoloEDescrizione_SanitizesBoth() {
            Event event = new Event();
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .titolo("<b>Progetto Alpha</b>")
                    .descrizione("Dettagli con &quot;virgolette&quot; e <i>corsivo</i>")
                    .build();

            mapper.impostaTitoloEDescrizione(event, request);

            assertEquals("Progetto Alpha", event.getSubject());
            assertNotNull(event.getBody());
            assertEquals("Dettagli con \"virgolette\" e corsivo", event.getBody().getContent());
            assertEquals(BodyType.Text, event.getBody().getContentType());
        }

        @Test
        @DisplayName("Se descrizione è null, non crea ItemBody")
        void testImpostaTitoloEDescrizione_NullDescriptionLeavesBodyNull() {
            Event event = new Event();
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .titolo("Progetto Beta")
                    .descrizione(null)
                    .build();

            mapper.impostaTitoloEDescrizione(event, request);

            assertEquals("Progetto Beta", event.getSubject());
            assertNull(event.getBody());
        }

        @Test
        @DisplayName("Dovrebbe lanciare NullPointerException se event o request sono null")
        void testImpostaTitoloEDescrizione_NullParameters_ThrowsNpe() {
            assertThrows(NullPointerException.class, () -> mapper.impostaTitoloEDescrizione(null, new PrenotazioneRequestDTO()));
            assertThrows(NullPointerException.class, () -> mapper.impostaTitoloEDescrizione(new Event(), null));
        }
    }
}

