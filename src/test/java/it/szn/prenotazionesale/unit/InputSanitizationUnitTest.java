package it.szn.prenotazionesale.unit;

import it.szn.prenotazionesale.mapper.PrenotazioneMapper;
import it.szn.prenotazionesale.validator.PrenotazioneValidator;
import com.microsoft.graph.models.*;
import it.szn.prenotazionesale.graph.GraphCalendarGateway;
import it.szn.prenotazionesale.config.AppConfig;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.model.SalaDTO;
import it.szn.prenotazionesale.service.PrenotazioniService;
import it.szn.prenotazionesale.service.SaleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Test isolati per la validazione e sanitizzazione degli input:
 * - Prevenzione OData Injection nei filtri temporali e query su Microsoft Graph.
 * - Protezione da OData Injection nel parametro iCalUId durante la cancellazione degli eventi.
 * - Protezione BOLA/IDOR e injection sui nomi delle caselle di posta delle sale.
 * - Sanitizzazione HTML / XSS per descrizioni e corpi degli eventi.
 * - Convalida e protezione da manomissione dei parametri di ricorrenza.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
public class InputSanitizationUnitTest {

    @Mock
    private GraphCalendarGateway calendarGateway;

    @Mock
    private SaleService saleService;

    private AppConfig appConfig;
    private ThreadPoolTaskExecutor executor;
    private PrenotazioniService prenotazioniService;

    private static final String SALA_VALIDA = "sala-dohrn@szn.it";

    @BeforeEach
    void setUp() {
        appConfig = new AppConfig();
        appConfig.setAdminRole("RoomBooking.Admin");

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.initialize();

        when(saleService.getSale()).thenReturn(List.of(
                SalaDTO.builder().id(SALA_VALIDA).nome("Sala Dohrn").email(SALA_VALIDA).build()
        ));

        PrenotazioneValidator validator = new PrenotazioneValidator();
        PrenotazioneMapper mapper = new PrenotazioneMapper(validator);
        prenotazioniService = new PrenotazioniService(calendarGateway, saleService, appConfig, executor, validator, mapper);
    }

    private Jwt createAdminJwt() {
        return new Jwt(
                "token-mock",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", "admin@szn.it", "roles", List.of("RoomBooking.Admin"))
        );
    }

    @Nested
    @DisplayName("1. Protezione da OData Injection nei Filtri di Data (calendarView)")
    class ODataDateFilterInjectionTests {

        @Test
        @DisplayName("Dovrebbe respingere tentativo di OData Injection con apice singolo e condizione 'or 1 eq 1'")
        void testODataInjection_SingleQuoteCondition_ThrowsIllegalArgument() {
            String maliciousDate = "2026-11-10T08:00:00' or 1 eq 1 or '";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.getPrenotazioni(SALA_VALIDA, maliciousDate, "2026-11-10T18:00:00", createAdminJwt())
            );
            assertThat(ex.getMessage()).contains("deve essere una data/ora in formato ISO-8601 valido");
        }

        @Test
        @DisplayName("Dovrebbe respingere tentativo di OData Injection per alterare il predicato endDateTime")
        void testODataInjection_FilterBypassAttempt_ThrowsIllegalArgument() {
            String maliciousDate = "2026-11-10T08:00:00' or endDateTime ge '2026-01-01T00:00:00Z";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.getPrenotazioni(SALA_VALIDA, maliciousDate, "2026-11-10T18:00:00", createAdminJwt())
            );
            assertThat(ex.getMessage()).contains("deve essere una data/ora in formato ISO-8601 valido");
        }

        @Test
        @DisplayName("Dovrebbe respingere payload SQL Injection o tentativi di concatenazione di comandi")
        void testODataInjection_SqlInjectionPayload_ThrowsIllegalArgument() {
            String maliciousDate = "2026-11-10T08:00:00; DROP TABLE events;--";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.getPrenotazioni(SALA_VALIDA, maliciousDate, "2026-11-10T18:00:00", createAdminJwt())
            );
            assertThat(ex.getMessage()).contains("deve essere una data/ora in formato ISO-8601 valido");
        }

        @Test
        @DisplayName("Dovrebbe respingere payload XSS con tag <script> nel parametro data")
        void testODataInjection_ScriptTagPayload_ThrowsIllegalArgument() {
            String maliciousDate = "<script>alert('xss')</script>";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.getPrenotazioni(SALA_VALIDA, maliciousDate, "2026-11-10T18:00:00", createAdminJwt())
            );
            assertThat(ex.getMessage()).contains("deve essere una data/ora in formato ISO-8601 valido");
        }

        @Test
        @DisplayName("Dovrebbe respingere newline o caratteri di controllo (CRLF injection) nei filtri data")
        void testODataInjection_CrlfPayload_ThrowsIllegalArgument() {
            String maliciousDate = "2026-11-10T08:00:00\r\nPrefer: outlook.timezone=\"UTC\"";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.getPrenotazioni(SALA_VALIDA, maliciousDate, "2026-11-10T18:00:00", createAdminJwt())
            );
            assertThat(ex.getMessage()).contains("deve essere una data/ora in formato ISO-8601 valido");
        }

        @Test
        @DisplayName("Dovrebbe accettare date valide in formato ISO-8601 standard senza sollevare eccezioni")
        void testODataInjection_ValidIsoDates_Accepted() {
            when(calendarGateway.getEventi(eq(SALA_VALIDA), any(), any())).thenReturn(Collections.emptyList());

            assertDoesNotThrow(() ->
                    prenotazioniService.getPrenotazioni(SALA_VALIDA, "2026-11-10T08:00:00", "2026-11-10T18:00:00", createAdminJwt())
            );
        }
    }

    @Nested
    @DisplayName("2. Protezione da OData Injection nel Parametro iCalUId (cancellaPrenotazione)")
    class ODataICalUIdInjectionTests {

        @Test
        @DisplayName("Dovrebbe bloccare l'interrogazione dell'organizzatore se iCalUId contiene apici o payload OData injection")
        void testICalUIdSanitization_MaliciousPayload_IgnoresOrganizerQuery() {
            // Configura un evento appartenente a un organizzatore terzo
            Event mockEvent = new Event();
            mockEvent.setId("evt-malicious-uid");
            mockEvent.setIsOrganizer(false);

            Recipient orgRecipient = new Recipient();
            EmailAddress email = new EmailAddress();
            email.setAddress("organizzatore.esterno@szn.it");
            orgRecipient.setEmailAddress(email);
            mockEvent.setOrganizer(orgRecipient);

            // iCalUId contaminato con OData Injection
            mockEvent.setICalUId("validPrefix' or 1 eq 1 or '");

            when(calendarGateway.getEvento(eq(SALA_VALIDA), eq("evt-malicious-uid"), any(String[].class)))
                    .thenReturn(mockEvent);

            // Esegue cancellazione
            prenotazioniService.cancellaPrenotazione("evt-malicious-uid", SALA_VALIDA, "SERIE", null, createAdminJwt());

            // Verifica che il filtro verso la casella dell'organizzatore NON sia MAI stato chiamato
            // grazie al blocco preventivo di PATTERN_ICALUID_SICURO
            verify(calendarGateway, never()).cercaEventiPerFiltro(anyString(), anyString());

            // Ma la cancellazione diretta sulla sala deve essere stata eseguita
            verify(calendarGateway).eliminaEvento(eq(SALA_VALIDA), eq("evt-malicious-uid"));
        }

        @Test
        @DisplayName("Dovrebbe bloccare l'interrogazione dell'organizzatore se iCalUId contiene tag <script>")
        void testICalUIdSanitization_XssPayload_IgnoresOrganizerQuery() {
            Event mockEvent = new Event();
            mockEvent.setId("evt-xss-uid");
            mockEvent.setIsOrganizer(false);

            Recipient orgRecipient = new Recipient();
            EmailAddress email = new EmailAddress();
            email.setAddress("organizzatore.esterno@szn.it");
            orgRecipient.setEmailAddress(email);
            mockEvent.setOrganizer(orgRecipient);
            mockEvent.setICalUId("<script>alert('xss')</script>");

            when(calendarGateway.getEvento(eq(SALA_VALIDA), eq("evt-xss-uid"), any(String[].class)))
                    .thenReturn(mockEvent);

            prenotazioniService.cancellaPrenotazione("evt-xss-uid", SALA_VALIDA, "SERIE", null, createAdminJwt());

            verify(calendarGateway, never()).cercaEventiPerFiltro(anyString(), anyString());
            verify(calendarGateway).eliminaEvento(eq(SALA_VALIDA), eq("evt-xss-uid"));
        }

        @Test
        @DisplayName("Dovrebbe consentire ed elaborare correttamente un iCalUId lecito conforme allo standard")
        void testICalUIdSanitization_ValidICalUId_ProceedsWithOrganizerDeletion() {
            Event mockEvent = new Event();
            mockEvent.setId("evt-legit-uid");
            mockEvent.setIsOrganizer(false);

            Recipient orgRecipient = new Recipient();
            EmailAddress email = new EmailAddress();
            email.setAddress("organizzatore.esterno@szn.it");
            orgRecipient.setEmailAddress(email);
            mockEvent.setOrganizer(orgRecipient);

            String validUid = "040000008200E00074C5B7101A82E00800000000E07137D024B1D601000000000000000010000000455B1DEB6DBA414EB6056554F85DF2B5";
            mockEvent.setICalUId(validUid);

            when(calendarGateway.getEvento(eq(SALA_VALIDA), eq("evt-legit-uid"), any(String[].class)))
                    .thenReturn(mockEvent);

            Event orgMatchingEvent = new Event();
            orgMatchingEvent.setId("org-evt-1");
            orgMatchingEvent.setICalUId(validUid);

            when(calendarGateway.cercaEventiPerFiltro(eq("organizzatore.esterno@szn.it"), any()))
                    .thenReturn(List.of(orgMatchingEvent));

            prenotazioniService.cancellaPrenotazione("evt-legit-uid", SALA_VALIDA, "SERIE", null, createAdminJwt());

            // Con UID valido, la cancellazione dell'evento sul calendario dell'organizzatore DEVE essere eseguita
            verify(calendarGateway).eliminaEvento("organizzatore.esterno@szn.it", "org-evt-1");
            verify(calendarGateway).eliminaEvento(SALA_VALIDA, "evt-legit-uid");
        }
    }

    @Nested
    @DisplayName("3. Protezione BOLA/IDOR e Injection su Caselle di Posta delle Sale")
    class MailboxSanitizationTests {

        @Test
        @DisplayName("Dovrebbe respingere tentativi di SQL/OData injection nel parametro salaEmail")
        void testMailboxSanitization_SqlInjection_ThrowsIllegalArgument() {
            String maliciousEmail = "sala-dohrn@szn.it' or '1'='1";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.getPrenotazioni(maliciousEmail, "2026-11-10T08:00:00", "2026-11-10T18:00:00", createAdminJwt())
            );
            assertThat(ex.getMessage()).contains("non e autorizzata o inesistente");
        }

        @Test
        @DisplayName("Dovrebbe respingere tentativi di Path Traversal nel parametro salaEmail")
        void testMailboxSanitization_PathTraversal_ThrowsIllegalArgument() {
            String maliciousEmail = "../../etc/passwd";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.getPrenotazioni(maliciousEmail, "2026-11-10T08:00:00", "2026-11-10T18:00:00", createAdminJwt())
            );
            assertThat(ex.getMessage()).contains("non e autorizzata o inesistente");
        }

        @Test
        @DisplayName("Dovrebbe respingere salaEmail vuota, blank o null")
        void testMailboxSanitization_EmptyOrNull_ThrowsIllegalArgument() {
            assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.getPrenotazioni("", "2026-11-10T08:00:00", "2026-11-10T18:00:00", createAdminJwt())
            );
            assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.getPrenotazioni("   ", "2026-11-10T08:00:00", "2026-11-10T18:00:00", createAdminJwt())
            );
            assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.getPrenotazioni(null, "2026-11-10T08:00:00", "2026-11-10T18:00:00", createAdminJwt())
            );
        }
    }

    @Nested
    @DisplayName("4. Sanitizzazione HTML / XSS e Decodifica Entità")
    class HtmlAndXssSanitizationTests {

        @Test
        @DisplayName("Dovrebbe rimuovere completamente tag HTML e script maligni dalla descrizione dell'evento (stripHtml)")
        void testHtmlSanitization_ScriptAndFormattingTagsStripped() {
            Event event = new Event();
            event.setId("evt-html-test");
            event.setSubject("Meeting con HTML");

            ItemBody body = new ItemBody();
            body.setContentType(BodyType.Html);
            body.setContent("<p>Descrizione di prova con <b>grassetto</b>.<script>alert('XSS')</script></p>");
            event.setBody(body);

            when(calendarGateway.getEventi(eq(SALA_VALIDA), any(), any())).thenReturn(List.of(event));

            List<PrenotazioneDTO> result = prenotazioniService.getPrenotazioni(
                    SALA_VALIDA, "2026-11-10T08:00:00", "2026-11-10T18:00:00", createAdminJwt()
            );

            assertThat(result).hasSize(1);
            String pulita = result.get(0).getDescrizione();

            assertThat(pulita)
                    .doesNotContain("<script>")
                    .doesNotContain("</script>")
                    .doesNotContain("<p>")
                    .doesNotContain("</p>")
                    .doesNotContain("<b>")
                    .doesNotContain("</b>")
                    .contains("Descrizione di prova con grassetto.");
        }

        @Test
        @DisplayName("Dovrebbe gestire tag img con payload onerror senza backtracking o loop")
        void testHtmlSanitization_ImgOnError_StrippedSafely() {
            Event event = new Event();
            event.setId("evt-img-test");
            event.setSubject("Test Img XSS");

            ItemBody body = new ItemBody();
            body.setContent("Note riunione: <img src='x' onerror='alert(document.domain)'> completate.");
            event.setBody(body);

            when(calendarGateway.getEventi(eq(SALA_VALIDA), any(), any())).thenReturn(List.of(event));

            List<PrenotazioneDTO> result = prenotazioniService.getPrenotazioni(
                    SALA_VALIDA, "2026-11-10T08:00:00", "2026-11-10T18:00:00", createAdminJwt()
            );

            assertThat(result.get(0).getDescrizione())
                    .doesNotContain("<img")
                    .doesNotContain("onerror")
                    .contains("Note riunione: completate.");
        }

        @Test
        @DisplayName("Dovrebbe decodificare correttamente entità HTML (&amp;, &quot;, &#39;, &nbsp;)")
        void testHtmlSanitization_HtmlEntitiesDecoded() {
            Event event = new Event();
            event.setId("evt-entities-test");
            event.setSubject("Test Entità");

            ItemBody body = new ItemBody();
            body.setContent("Ricerca &amp; Sviluppo &quot;SZN&quot; &#39;Napoli&#39;&nbsp;2026");
            event.setBody(body);

            when(calendarGateway.getEventi(eq(SALA_VALIDA), any(), any())).thenReturn(List.of(event));

            List<PrenotazioneDTO> result = prenotazioniService.getPrenotazioni(
                    SALA_VALIDA, "2026-11-10T08:00:00", "2026-11-10T18:00:00", createAdminJwt()
            );

            assertThat(result.get(0).getDescrizione())
                    .isEqualTo("Ricerca & Sviluppo \"SZN\" 'Napoli' 2026");
        }
    }

    @Nested
    @DisplayName("5. Convalida e Protezione da Manomissione dei Parametri di Ricorrenza")
    class RecurrenceTamperingTests {

        @Test
        @DisplayName("Dovrebbe respingere tipo di pattern ricorrenza non consentito o con injection")
        void testRecurrence_InvalidPattern_ThrowsIllegalArgument() {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_VALIDA)
                    .titolo("Meeting Ricorrente")
                    .start("2026-11-10T09:00:00")
                    .end("2026-11-10T10:00:00")
                    .pattern("daily' OR '1'='1")
                    .dataFine("2026-11-20")
                    .build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt())
            );
            assertThat(ex.getMessage()).contains("Tipo di ricorrenza non valido");
        }

        @Test
        @DisplayName("Dovrebbe respingere giorni della settimana con caratteri di injection")
        void testRecurrence_InvalidDaysOfWeek_ThrowsIllegalArgument() {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_VALIDA)
                    .titolo("Meeting Settimanale Injection")
                    .start("2026-11-10T09:00:00")
                    .end("2026-11-10T10:00:00")
                    .pattern("weekly")
                    .giorniSettimana(List.of("MONDAY", "WEDNESDAY; DROP TABLE"))
                    .dataFine("2026-11-20")
                    .build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt())
            );
            assertThat(ex.getMessage()).contains("Giorno della settimana non valido");
        }

        @Test
        @DisplayName("Dovrebbe respingere intervalli negativi o superiori al massimo consentito (52)")
        void testRecurrence_OutOfRangeInterval_ThrowsIllegalArgument() {
            PrenotazioneRequestDTO reqNegativo = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_VALIDA)
                    .titolo("Meeting Intervallo Negativo")
                    .start("2026-11-10T09:00:00")
                    .end("2026-11-10T10:00:00")
                    .pattern("daily")
                    .intervallo(-1)
                    .dataFine("2026-11-20")
                    .build();

            IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(reqNegativo, createAdminJwt())
            );
            assertThat(ex1.getMessage()).contains("L'intervallo di ricorrenza deve essere compreso tra 1 e 52");

            PrenotazioneRequestDTO reqEccessivo = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_VALIDA)
                    .titolo("Meeting Intervallo Eccessivo")
                    .start("2026-11-10T09:00:00")
                    .end("2026-11-10T10:00:00")
                    .pattern("daily")
                    .intervallo(100)
                    .dataFine("2026-11-20")
                    .build();

            IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(reqEccessivo, createAdminJwt())
            );
            assertThat(ex2.getMessage()).contains("L'intervallo di ricorrenza deve essere compreso tra 1 e 52");
        }

        @Test
        @DisplayName("Dovrebbe respingere data fine ricorrenza antecedente alla data di inizio")
        void testRecurrence_EndDateBeforeStartDate_ThrowsIllegalArgument() {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_VALIDA)
                    .titolo("Data Fine Invertita")
                    .start("2026-11-10T09:00:00")
                    .end("2026-11-10T10:00:00")
                    .pattern("daily")
                    .dataFine("2026-11-01")
                    .build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt())
            );
            assertThat(ex.getMessage()).contains("deve essere successiva o uguale alla data di inizio");
        }
    }
}
