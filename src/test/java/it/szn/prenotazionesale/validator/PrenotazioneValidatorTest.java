package it.szn.prenotazionesale.validator;

import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test di unità per PrenotazioneValidator.
 * Verifica in modo isolato e deterministico, senza mock di Microsoft Graph,
 * la sanitizzazione degli input, le regole di fascia oraria, le ricorrenze e la generazione delle occorrenze.
 */
public class PrenotazioneValidatorTest {

    private PrenotazioneValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PrenotazioneValidator();
    }

    @Nested
    @DisplayName("1. Escape OData")
    class EscapeODataTests {

        @Test
        @DisplayName("Dovrebbe raddoppiare gli apici singoli per prevenire injection OData")
        void testEscapeODataString_DoublesSingleQuotes() {
            assertEquals("O''Connor", validator.escapeODataString("O'Connor"));
            assertEquals("test'' injection", validator.escapeODataString("test' injection"));
        }

        @Test
        @DisplayName("Dovrebbe gestire null e stringhe senza apici")
        void testEscapeODataString_NullAndNormalString() {
            assertEquals("", validator.escapeODataString(null));
            assertEquals("normal string", validator.escapeODataString("normal string"));
        }
    }

    @Nested
    @DisplayName("2. Validazione Parametri Filtro Data e Intervalli")
    class DateFilterValidationTests {

        @Test
        @DisplayName("Dovrebbe accettare date valide in formato ISO-8601 (locale, UTC o con offset)")
        void testValidaParametroFiltroData_ValidFormats() {
            assertDoesNotThrow(() -> validator.validaParametroFiltroData("2026-11-10T08:00:00", "dataInizio"));
            assertDoesNotThrow(() -> validator.validaParametroFiltroData("2026-11-10T08:00:00Z", "dataInizio"));
            assertDoesNotThrow(() -> validator.validaParametroFiltroData("2026-11-10T08:00:00+01:00", "dataInizio"));
            assertDoesNotThrow(() -> validator.validaParametroFiltroData("2026-11-10T08:00:00.123Z", "dataInizio"));
        }

        @Test
        @DisplayName("Dovrebbe respingere formati non validi o tentativi di injection")
        void testValidaParametroFiltroData_InvalidFormats() {
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validaParametroFiltroData("2026-11-10", "dataInizio"));
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validaParametroFiltroData("2026-11-10T08:00:00' or 1 eq 1", "dataInizio"));
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validaParametroFiltroData("<script>alert(1)</script>", "dataInizio"));
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validaParametroFiltroData(null, "dataInizio"));
        }

        @Test
        @DisplayName("Dovrebbe convalidare intervallo coerente e respingere inizio successivo o uguale a fine")
        void testValidaIntervalloDate_OrderValidation() {
            assertDoesNotThrow(() -> validator.validaIntervalloDate("2026-11-10T08:00:00", "2026-11-10T18:00:00"));

            IllegalArgumentException exEqual = assertThrows(IllegalArgumentException.class,
                    () -> validator.validaIntervalloDate("2026-11-10T08:00:00", "2026-11-10T08:00:00"));
            assertThat(exEqual.getMessage()).contains("antecedente");

            IllegalArgumentException exInverted = assertThrows(IllegalArgumentException.class,
                    () -> validator.validaIntervalloDate("2026-11-10T18:00:00", "2026-11-10T08:00:00"));
            assertThat(exInverted.getMessage()).contains("antecedente");
        }

        @Test
        @DisplayName("Dovrebbe respingere intervalli temporali superiori al massimo consentito (370 giorni)")
        void testValidaIntervalloDate_MaxRangeExceeded() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validator.validaIntervalloDate("2026-01-01T00:00:00", "2027-02-15T00:00:00"));
            assertThat(ex.getMessage()).contains("non puo superare");
        }
    }

    @Nested
    @DisplayName("3. Sanitizzazione Contenuto Testuale e XSS")
    class TextContentSanitizationTests {

        @Test
        @DisplayName("Dovrebbe respingere richieste nulle o senza titolo")
        void testValidaContenutoTestuale_NullOrEmpty() {
            assertThrows(IllegalArgumentException.class, () -> validator.validaContenutoTestuale(null));

            PrenotazioneRequestDTO emptyTitle = PrenotazioneRequestDTO.builder().titolo("   ").build();
            assertThrows(IllegalArgumentException.class, () -> validator.validaContenutoTestuale(emptyTitle));
        }

        @Test
        @DisplayName("Dovrebbe respingere payload XSS pericolosi nel titolo o nella descrizione")
        void testValidaContenutoTestuale_XSS() {
            PrenotazioneRequestDTO scriptInTitle = PrenotazioneRequestDTO.builder()
                    .titolo("<script>alert('xss')</script>")
                    .build();
            assertThrows(IllegalArgumentException.class, () -> validator.validaContenutoTestuale(scriptInTitle));

            PrenotazioneRequestDTO javascriptInDesc = PrenotazioneRequestDTO.builder()
                    .titolo("Titolo Valido")
                    .descrizione("<a href=\"javascript:evil()\">Link</a>")
                    .build();
            assertThrows(IllegalArgumentException.class, () -> validator.validaContenutoTestuale(javascriptInDesc));

            PrenotazioneRequestDTO onloadInDesc = PrenotazioneRequestDTO.builder()
                    .titolo("Titolo Valido")
                    .descrizione("<svg onload=alert(1)>")
                    .build();
            assertThrows(IllegalArgumentException.class, () -> validator.validaContenutoTestuale(onloadInDesc));
        }

        @Test
        @DisplayName("Dovrebbe respingere titolo che contiene unicamente tag HTML")
        void testValidaContenutoTestuale_OnlyHtmlTags() {
            PrenotazioneRequestDTO onlyTags = PrenotazioneRequestDTO.builder()
                    .titolo("<b>   </b>")
                    .build();
            assertThrows(IllegalArgumentException.class, () -> validator.validaContenutoTestuale(onlyTags));
        }

        @Test
        @DisplayName("Dovrebbe accettare contenuti leciti")
        void testValidaContenutoTestuale_Valid() {
            PrenotazioneRequestDTO valid = PrenotazioneRequestDTO.builder()
                    .titolo("Riunione Direttivo")
                    .descrizione("Pianificazione strategica Q1")
                    .build();
            assertDoesNotThrow(() -> validator.validaContenutoTestuale(valid));
        }
    }

    @Nested
    @DisplayName("4. Parsing Data/Ora e Normalizzazione Fuso Orario Roma")
    class TimezoneParsingTests {

        @Test
        @DisplayName("Dovrebbe eseguire il parsing corretto di LocalDateTime locale")
        void testParseIsoToRomeLocalDateTime_Local() {
            LocalDateTime dt = validator.parseIsoToRomeLocalDateTime("2026-11-10T10:30:00");
            assertEquals(2026, dt.getYear());
            assertEquals(11, dt.getMonthValue());
            assertEquals(10, dt.getDayOfMonth());
            assertEquals(10, dt.getHour());
            assertEquals(30, dt.getMinute());
        }

        @Test
        @DisplayName("Dovrebbe convertire timestamp UTC nel fuso Europe/Rome (+1 in inverno)")
        void testParseIsoToRomeLocalDateTime_UtcWinter() {
            // In novembre l'Italia è su CET (UTC+1)
            LocalDateTime dt = validator.parseIsoToRomeLocalDateTime("2026-11-10T09:00:00Z");
            assertEquals(10, dt.getHour());
        }

        @Test
        @DisplayName("Dovrebbe convertire timestamp UTC nel fuso Europe/Rome (+2 in estate)")
        void testParseIsoToRomeLocalDateTime_UtcSummer() {
            // In luglio l'Italia è su CEST (UTC+2)
            LocalDateTime dt = validator.parseIsoToRomeLocalDateTime("2026-07-15T08:00:00Z");
            assertEquals(10, dt.getHour());
        }

        @Test
        @DisplayName("Dovrebbe sollevare eccezione su stringa vuota o non valida")
        void testParseIsoToRomeLocalDateTime_Invalid() {
            assertThrows(IllegalArgumentException.class, () -> validator.parseIsoToRomeLocalDateTime(null));
            assertThrows(IllegalArgumentException.class, () -> validator.parseIsoToRomeLocalDateTime("   "));
            assertThrows(Exception.class, () -> validator.parseIsoToRomeLocalDateTime("invalid-date"));
        }
    }

    @Nested
    @DisplayName("5. Regole di Fascia Oraria Aziendale (08:00 - 20:00)")
    class BusinessHoursTests {

        private String getFutureDateString(int plusDays, String timeStr) {
            LocalDate d = LocalDate.now().plusDays(plusDays);
            return d.format(DateTimeFormatter.ISO_LOCAL_DATE) + "T" + timeStr;
        }

        @Test
        @DisplayName("Dovrebbe consentire orari validi all'interno della fascia lavorativa")
        void testValidaOrariPrenotazione_ValidSlot() {
            String start = getFutureDateString(5, "09:00:00");
            String end = getFutureDateString(5, "11:00:00");
            assertDoesNotThrow(() -> validator.validaOrariPrenotazione(start, end));
        }

        @Test
        @DisplayName("Dovrebbe respingere fine antecedente o uguale all'inizio")
        void testValidaOrariPrenotazione_EndBeforeOrEqualStart() {
            String start = getFutureDateString(5, "11:00:00");
            String endEqual = getFutureDateString(5, "11:00:00");
            String endBefore = getFutureDateString(5, "10:00:00");

            IllegalArgumentException exEqual = assertThrows(IllegalArgumentException.class,
                    () -> validator.validaOrariPrenotazione(start, endEqual));
            assertThat(exEqual.getMessage()).contains("orario di fine deve essere successivo");

            IllegalArgumentException exBefore = assertThrows(IllegalArgumentException.class,
                    () -> validator.validaOrariPrenotazione(start, endBefore));
            assertThat(exBefore.getMessage()).contains("orario di fine deve essere successivo");
        }

        @Test
        @DisplayName("Dovrebbe respingere prenotazioni multi-giorno")
        void testValidaOrariPrenotazione_MultiDay() {
            String start = getFutureDateString(5, "10:00:00");
            String end = getFutureDateString(6, "11:00:00");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validator.validaOrariPrenotazione(start, end));
            assertThat(ex.getMessage()).contains("stessa giornata lavorativa");
        }

        @Test
        @DisplayName("Dovrebbe respingere prenotazioni fuori fascia aziendale 08:00 - 20:00")
        void testValidaOrariPrenotazione_OutsideBusinessHours() {
            // Inizio prima delle 08:00
            String startEarly = getFutureDateString(5, "07:30:00");
            String endEarly = getFutureDateString(5, "09:00:00");
            assertThrows(IllegalArgumentException.class, () -> validator.validaOrariPrenotazione(startEarly, endEarly));

            // Fine dopo le 20:00
            String startLate = getFutureDateString(5, "19:00:00");
            String endLate = getFutureDateString(5, "20:30:00");
            assertThrows(IllegalArgumentException.class, () -> validator.validaOrariPrenotazione(startLate, endLate));

            // Notturna
            String startNight = getFutureDateString(5, "22:00:00");
            String endNight = getFutureDateString(5, "23:00:00");
            assertThrows(IllegalArgumentException.class, () -> validator.validaOrariPrenotazione(startNight, endNight));
        }

        @Test
        @DisplayName("Dovrebbe respingere prenotazioni nel passato")
        void testValidaOrariPrenotazione_PastDate() {
            String startPast = "2020-01-10T10:00:00";
            String endPast = "2020-01-10T11:00:00";

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validator.validaOrariPrenotazione(startPast, endPast));
            assertThat(ex.getMessage()).contains("gia trascorsi");
        }
    }

    @Nested
    @DisplayName("6. Validazione Ricorrenze")
    class RecurrenceValidationTests {

        private String getFutureDateString(int plusDays, String timeStr) {
            LocalDate d = LocalDate.now().plusDays(plusDays);
            return d.format(DateTimeFormatter.ISO_LOCAL_DATE) + "T" + timeStr;
        }

        @Test
        @DisplayName("Dovrebbe respingere pattern di ricorrenza nullo o non valido")
        void testValidaRicorrenza_InvalidPattern() {
            PrenotazioneRequestDTO noPattern = PrenotazioneRequestDTO.builder()
                    .start(getFutureDateString(5, "10:00:00"))
                    .dataFine(getFutureDateString(10, "10:00:00"))
                    .build();
            assertThrows(IllegalArgumentException.class, () -> validator.validaRicorrenza(noPattern));

            PrenotazioneRequestDTO unknownPattern = PrenotazioneRequestDTO.builder()
                    .pattern("yearly")
                    .start(getFutureDateString(5, "10:00:00"))
                    .dataFine(getFutureDateString(10, "10:00:00"))
                    .build();
            assertThrows(IllegalArgumentException.class, () -> validator.validaRicorrenza(unknownPattern));
        }

        @Test
        @DisplayName("Dovrebbe respingere data fine ricorrenza antecedente alla data di inizio")
        void testValidaRicorrenza_EndDateBeforeStart() {
            PrenotazioneRequestDTO req = PrenotazioneRequestDTO.builder()
                    .pattern("daily")
                    .start(getFutureDateString(10, "10:00:00"))
                    .dataFine(getFutureDateString(5, "10:00:00"))
                    .build();
            assertThrows(IllegalArgumentException.class, () -> validator.validaRicorrenza(req));
        }

        @Test
        @DisplayName("Dovrebbe respingere ricorrenze superiori a 365 giorni (1 anno)")
        void testValidaRicorrenza_ExceedsOneYear() {
            LocalDate start = LocalDate.now().plusDays(5);
            LocalDate end = start.plusDays(366);

            PrenotazioneRequestDTO req = PrenotazioneRequestDTO.builder()
                    .pattern("daily")
                    .start(start + "T10:00:00")
                    .dataFine(end + "T10:00:00")
                    .build();
            assertThrows(IllegalArgumentException.class, () -> validator.validaRicorrenza(req));
        }

        @Test
        @DisplayName("Dovrebbe validare i giorni della settimana per ricorrenza weekly")
        void testValidaRicorrenza_WeeklyDays() {
            // Weekly senza giorni
            PrenotazioneRequestDTO noDays = PrenotazioneRequestDTO.builder()
                    .pattern("weekly")
                    .start(getFutureDateString(5, "10:00:00"))
                    .dataFine(getFutureDateString(30, "10:00:00"))
                    .build();
            assertThrows(IllegalArgumentException.class, () -> validator.validaRicorrenza(noDays));

            // Weekly con giorno non valido
            PrenotazioneRequestDTO invalidDay = PrenotazioneRequestDTO.builder()
                    .pattern("weekly")
                    .start(getFutureDateString(5, "10:00:00"))
                    .dataFine(getFutureDateString(30, "10:00:00"))
                    .giorniSettimana(List.of("LUNEDI"))
                    .build();
            assertThrows(IllegalArgumentException.class, () -> validator.validaRicorrenza(invalidDay));

            // Weekly valido
            PrenotazioneRequestDTO validWeekly = PrenotazioneRequestDTO.builder()
                    .pattern("weekly")
                    .start(getFutureDateString(5, "10:00:00"))
                    .dataFine(getFutureDateString(30, "10:00:00"))
                    .giorniSettimana(List.of("MONDAY", "WEDNESDAY"))
                    .build();
            assertDoesNotThrow(() -> validator.validaRicorrenza(validWeekly));
        }

        @Test
        @DisplayName("Dovrebbe validare l'intervallo compreso tra 1 e 52")
        void testValidaRicorrenza_IntervalRange() {
            PrenotazioneRequestDTO negativeInterval = PrenotazioneRequestDTO.builder()
                    .pattern("daily")
                    .start(getFutureDateString(5, "10:00:00"))
                    .dataFine(getFutureDateString(30, "10:00:00"))
                    .intervallo(0)
                    .build();
            assertThrows(IllegalArgumentException.class, () -> validator.validaRicorrenza(negativeInterval));

            PrenotazioneRequestDTO excessiveInterval = PrenotazioneRequestDTO.builder()
                    .pattern("daily")
                    .start(getFutureDateString(5, "10:00:00"))
                    .dataFine(getFutureDateString(30, "10:00:00"))
                    .intervallo(53)
                    .build();
            assertThrows(IllegalArgumentException.class, () -> validator.validaRicorrenza(excessiveInterval));
        }
    }

    @Nested
    @DisplayName("7. Sanitizzazione HTML (stripHtml)")
    class StripHtmlTests {

        @Test
        @DisplayName("Dovrebbe gestire stringhe nulle o vuote")
        void testStripHtml_NullOrBlank() {
            assertNull(validator.stripHtml(null));
            assertNull(validator.stripHtml(""));
            assertNull(validator.stripHtml("   "));
        }

        @Test
        @DisplayName("Dovrebbe rimuovere tag script e style con il loro contenuto")
        void testStripHtml_RemoveScriptAndStyle() {
            String html = "<div>Testo <script>alert('hack');</script>visibile<style>body{color:red;}</style></div>";
            assertEquals("Testo visibile", validator.stripHtml(html));
        }

        @Test
        @DisplayName("Dovrebbe convertire tag di linea e paragrafo in ritorni a capo")
        void testStripHtml_BreaksAndParagraphs() {
            String html = "<p>Primo paragrafo</p><p>Secondo paragrafo<br/>Riga successiva</p>";
            String expected = "Primo paragrafo\n\nSecondo paragrafo\nRiga successiva";
            assertEquals(expected, validator.stripHtml(html));
        }

        @Test
        @DisplayName("Dovrebbe decodificare le entità HTML comuni")
        void testStripHtml_HtmlEntities() {
            String html = "Tom &amp; Jerry &quot;quote&#39; &nbsp; space";
            assertEquals("Tom & Jerry \"quote' space", validator.stripHtml(html));
        }
    }

    @Nested
    @DisplayName("8. Generazione Occorrenze")
    class GeneraOccorrenzeTests {

        @Test
        @DisplayName("Dovrebbe generare 1 sola occorrenza per eventi singoli o tipoModifica SINGOLA")
        void testGeneraOccorrenze_SingleEvent() {
            PrenotazioneRequestDTO req = PrenotazioneRequestDTO.builder()
                    .start("2026-11-10T10:00:00")
                    .end("2026-11-10T11:00:00")
                    .build();
            List<LocalDateTime[]> occorrenze = validator.generaOccorrenze(req);
            assertEquals(1, occorrenze.size());
            assertEquals(LocalDateTime.parse("2026-11-10T10:00:00"), occorrenze.get(0)[0]);
            assertEquals(LocalDateTime.parse("2026-11-10T11:00:00"), occorrenze.get(0)[1]);

            PrenotazioneRequestDTO reqModificaSingola = PrenotazioneRequestDTO.builder()
                    .start("2026-11-10T10:00:00")
                    .end("2026-11-10T11:00:00")
                    .pattern("daily")
                    .dataFine("2026-11-20T10:00:00")
                    .tipoModifica("SINGOLA")
                    .build();
            List<LocalDateTime[]> occSingola = validator.generaOccorrenze(reqModificaSingola);
            assertEquals(1, occSingola.size());
        }

        @Test
        @DisplayName("Dovrebbe generare correttamente le occorrenze per pattern daily")
        void testGeneraOccorrenze_Daily() {
            PrenotazioneRequestDTO req = PrenotazioneRequestDTO.builder()
                    .start("2026-11-10T10:00:00")
                    .end("2026-11-10T11:30:00")
                    .pattern("daily")
                    .dataFine("2026-11-14T10:00:00")
                    .intervallo(1)
                    .build();

            List<LocalDateTime[]> occ = validator.generaOccorrenze(req);
            assertEquals(5, occ.size()); // 10, 11, 12, 13, 14
            assertEquals(LocalDateTime.parse("2026-11-10T10:00:00"), occ.get(0)[0]);
            assertEquals(LocalDateTime.parse("2026-11-10T11:30:00"), occ.get(0)[1]);
            assertEquals(LocalDateTime.parse("2026-11-14T10:00:00"), occ.get(4)[0]);
        }

        @Test
        @DisplayName("Dovrebbe generare correttamente le occorrenze per pattern weekly")
        void testGeneraOccorrenze_Weekly() {
            // 2026-11-09 è Lunedì
            PrenotazioneRequestDTO req = PrenotazioneRequestDTO.builder()
                    .start("2026-11-09T10:00:00")
                    .end("2026-11-09T11:00:00")
                    .pattern("weekly")
                    .giorniSettimana(List.of("MONDAY", "WEDNESDAY"))
                    .dataFine("2026-11-22T10:00:00") // 2 settimane (9, 11, 16, 18)
                    .intervallo(1)
                    .build();

            List<LocalDateTime[]> occ = validator.generaOccorrenze(req);
            assertEquals(4, occ.size());
            assertEquals(LocalDateTime.parse("2026-11-09T10:00:00"), occ.get(0)[0]);
            assertEquals(LocalDateTime.parse("2026-11-11T10:00:00"), occ.get(1)[0]);
            assertEquals(LocalDateTime.parse("2026-11-16T10:00:00"), occ.get(2)[0]);
            assertEquals(LocalDateTime.parse("2026-11-18T10:00:00"), occ.get(3)[0]);
        }

        @Test
        @DisplayName("Dovrebbe generare correttamente le occorrenze per pattern monthly")
        void testGeneraOccorrenze_Monthly() {
            PrenotazioneRequestDTO req = PrenotazioneRequestDTO.builder()
                    .start("2026-11-10T10:00:00")
                    .end("2026-11-10T11:00:00")
                    .pattern("monthly")
                    .dataFine("2027-02-15T10:00:00")
                    .intervallo(1)
                    .build();

            List<LocalDateTime[]> occ = validator.generaOccorrenze(req);
            assertEquals(4, occ.size()); // 2026-11-10, 2026-12-10, 2027-01-10, 2027-02-10
            assertEquals(LocalDateTime.parse("2026-11-10T10:00:00"), occ.get(0)[0]);
            assertEquals(LocalDateTime.parse("2026-12-10T10:00:00"), occ.get(1)[0]);
            assertEquals(LocalDateTime.parse("2027-01-10T10:00:00"), occ.get(2)[0]);
            assertEquals(LocalDateTime.parse("2027-02-10T10:00:00"), occ.get(3)[0]);
        }
    }
}
