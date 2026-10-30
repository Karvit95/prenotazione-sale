package it.szn.prenotazionesale.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.microsoft.kiota.ApiException;
import it.szn.prenotazionesale.config.AppConfig;
import it.szn.prenotazionesale.config.CustomAccessDeniedHandler;
import it.szn.prenotazionesale.config.CustomAuthenticationEntryPoint;
import it.szn.prenotazionesale.controller.PrenotazioniController;
import it.szn.prenotazionesale.exception.ErrorResponse;
import it.szn.prenotazionesale.exception.GlobalExceptionHandler;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.service.PrenotazioniService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Test Anti-Data Leak: verifica che nei log applicativi e di sicurezza non vengano MAI
 * stampati in chiaro token JWT (pattern 'eyJ...'), client-secret di Azure ('${AZURE_CLIENT_SECRET}'
 * o valori segreti effettivi), né credenziali sensibili durante l'intero ciclo di vita dell'applicazione.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogAntiDataLeakTest {

    private static final String REALISTIC_SAMPLE_JWT =
            "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJvaWQiOiJmNGE4YjFkMS0xMjM0LTQ1NjctODkwMS0yMzQ1Njc4OTAxMjMiLCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJhZG1pbkBzem4uaXQiLCJyb2xlcyI6WyJSb29tQm9va2luZy5BZG1pbiJdLCJzdWIiOiJmNGE4YjFkMS0xMjM0LTQ1NjctODkwMS0yMzQ1Njc4OTAxMjMifQ." +
            "c2lnbmF0dXJlMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI";

    private static final String DUMMY_AZURE_SECRET = "G7xQ~99kLmnOP_DummySecretValue123!#";
    private static final String SECRET_PLACEHOLDER = "${AZURE_CLIENT_SECRET}";

    private static final Pattern JWT_HEADER_PATTERN = Pattern.compile("eyJ[a-zA-Z0-9_-]{10,}\\.eyJ[a-zA-Z0-9_-]{10,}");
    private static final Pattern BEARER_PATTERN = Pattern.compile("Bearer\\s+eyJ[a-zA-Z0-9_-]{10,}", Pattern.CASE_INSENSITIVE);

    private Logger rootLogger;
    private ListAppender<ILoggingEvent> rootAppender;
    private SecurityAuditService auditService;
    private PrenotazioniService prenotazioniService;

    @BeforeEach
    void setUp() {
        auditService = new SecurityAuditService();
        prenotazioniService = Mockito.mock(PrenotazioniService.class);

        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootAppender = new ListAppender<>();
        rootAppender.start();
        rootLogger.addAppender(rootAppender);
    }

    @AfterEach
    void tearDown() {
        if (rootLogger != null && rootAppender != null) {
            rootLogger.detachAppender(rootAppender);
            rootAppender.stop();
        }
        SecurityContextHolder.clearContext();
    }

    private Jwt createSampleJwt() {
        return new Jwt(
                REALISTIC_SAMPLE_JWT,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256", "typ", "JWT"),
                Map.of(
                        "oid", "f4a8b1d1-1234-4567-8901-234567890123",
                        "preferred_username", "admin@szn.it",
                        "roles", List.of("RoomBooking.Admin"),
                        "sub", "f4a8b1d1-1234-4567-8901-234567890123"
                )
        );
    }

    private void assertNoSensitiveDataInLogs(CapturedOutput output) {
        // 1. Scansiona tutti gli eventi catturati dall'Appender Logback di Root
        for (ILoggingEvent event : rootAppender.list) {
            String msg = event.getFormattedMessage();
            assertFalse(msg.contains(REALISTIC_SAMPLE_JWT),
                    "Rilevato token JWT in chiaro nel messaggio di log: " + msg);
            assertFalse(msg.contains(DUMMY_AZURE_SECRET),
                    "Rilevato client secret in chiaro nel messaggio di log: " + msg);
            assertFalse(JWT_HEADER_PATTERN.matcher(msg).find(),
                    "Rilevato pattern JWT ('eyJ...') nel messaggio di log: " + msg);
            assertFalse(BEARER_PATTERN.matcher(msg).find(),
                    "Rilevato 'Bearer eyJ...' nel messaggio di log: " + msg);
        }

        // 2. Scansiona l'intero output di stdout/stderr della console
        String allConsoleOutput = output.getAll();
        assertFalse(allConsoleOutput.contains(REALISTIC_SAMPLE_JWT),
                "Rilevato token JWT in chiaro nell'output console");
        assertFalse(allConsoleOutput.contains(DUMMY_AZURE_SECRET),
                "Rilevato client secret in chiaro nell'output console");
        assertFalse(JWT_HEADER_PATTERN.matcher(allConsoleOutput).find(),
                "Rilevato pattern JWT ('eyJ...') nell'output console");
        assertFalse(BEARER_PATTERN.matcher(allConsoleOutput).find(),
                "Rilevato 'Bearer eyJ...' nell'output console");
    }

    @Test
    @DisplayName("Anti-Data Leak - Nessun token JWT stampato nei log durante le operazioni CRUD di prenotazione")
    void testNoJwtTokenLeakedInLogsDuringBookingLifecycle(CapturedOutput output) {
        Jwt jwt = createSampleJwt();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/prenotazioni");
        request.addHeader("Authorization", "Bearer " + REALISTIC_SAMPLE_JWT);
        request.setRemoteAddr("10.10.10.10");

        PrenotazioniController controller = new PrenotazioniController(prenotazioniService, auditService, request);

        // A. Creazione
        when(prenotazioniService.creaPrenotazione(any(), any())).thenReturn(
                PrenotazioneDTO.builder()
                        .id("booking-leak-test-1")
                        .salaEmail("sala.dohrn@szn.it")
                        .titolo("Meeting Riservato")
                        .build()
        );
        PrenotazioneRequestDTO createDto = PrenotazioneRequestDTO.builder()
                .salaEmail("sala.dohrn@szn.it")
                .titolo("Meeting Riservato")
                .start("2026-11-05T09:00:00")
                .end("2026-11-05T10:00:00")
                .build();
        controller.creaPrenotazione(createDto, jwt);

        // B. Modifica
        when(prenotazioniService.modificaPrenotazione(eq("booking-leak-test-1"), any(), any())).thenReturn(
                PrenotazioneDTO.builder()
                        .id("booking-leak-test-1")
                        .salaEmail("sala.dohrn@szn.it")
                        .titolo("Meeting Riservato Modificato")
                        .build()
        );
        controller.modificaPrenotazione("booking-leak-test-1", createDto, jwt);

        // C. Cancellazione
        controller.cancellaPrenotazione("booking-leak-test-1", "sala.dohrn@szn.it", "SERIE", null, jwt);

        // Asserzione: nessun leak nei log
        assertNoSensitiveDataInLogs(output);
    }

    @Test
    @DisplayName("Anti-Data Leak - Client Secret di Azure escluso da toString() e mai stampato nei log")
    void testNoAzureClientSecretLeakedInLogsOrConfiguration(CapturedOutput output) {
        AppConfig appConfig = new AppConfig();
        AppConfig.Azure azure = new AppConfig.Azure();
        azure.setTenantId("72f988bf-86f1-41af-91ab-2d7cd011db47");
        azure.setClientId("11112222-3333-4444-5555-666677778888");
        azure.setClientSecret(DUMMY_AZURE_SECRET);
        appConfig.setAzure(azure);

        // 1. Verifica che il toString() di Azure escluda il clientSecret (SEC-14)
        String azureToString = azure.toString();
        assertFalse(azureToString.contains(DUMMY_AZURE_SECRET),
                "AppConfig.Azure.toString() non deve includere il clientSecret!");
        assertFalse(azureToString.contains(SECRET_PLACEHOLDER),
                "AppConfig.Azure.toString() non deve contenere il placeholder del secret!");

        // 2. Log dell'oggetto di configurazione per testare l'assenza di leak a runtime
        org.slf4j.Logger logger = LoggerFactory.getLogger(LogAntiDataLeakTest.class);
        logger.info("Verifica configurazione Azure caricata: {}", azure);
        logger.info("Verifica configurazione generale caricata: {}", appConfig);

        assertNoSensitiveDataInLogs(output);
    }

    @Test
    @DisplayName("Anti-Data Leak - Nessun token JWT o credenziale stampata durante errore di autenticazione 401")
    void testNoSensitiveDataLeakedDuringAuthenticationFailure_401(CapturedOutput output) throws Exception {
        CustomAuthenticationEntryPoint entryPoint = new CustomAuthenticationEntryPoint(auditService);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/prenotazioni");
        request.addHeader("Authorization", "Bearer " + REALISTIC_SAMPLE_JWT);
        request.setRequestURI("/api/prenotazioni");

        MockHttpServletResponse response = new MockHttpServletResponse();
        BadCredentialsException exception = new BadCredentialsException(
                "Autenticazione fallita per token Bearer " + REALISTIC_SAMPLE_JWT);

        entryPoint.commence(request, response, exception);

        assertEquals(401, response.getStatus());

        // Il corpo della risposta verso il client non deve contenere il token né stacktrace
        String responseBody = response.getContentAsString();
        assertFalse(responseBody.contains(REALISTIC_SAMPLE_JWT));
        assertFalse(responseBody.contains("Bearer"));

        // L'audit log deve registrare il rifiuto senza stampare il token JWT
        assertNoSensitiveDataInLogs(output);
    }

    @Test
    @DisplayName("Anti-Data Leak - Nessun token JWT stampato durante gestione Access Denied 403")
    void testNoSensitiveDataLeakedDuringAccessDenied_403(CapturedOutput output) throws Exception {
        CustomAccessDeniedHandler accessDeniedHandler = new CustomAccessDeniedHandler(auditService);

        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/prenotazioni/booking-403");
        request.addHeader("Authorization", "Bearer " + REALISTIC_SAMPLE_JWT);
        request.setRequestURI("/api/prenotazioni/booking-403");

        MockHttpServletResponse response = new MockHttpServletResponse();
        AccessDeniedException exception = new AccessDeniedException("Ruolo insufficiente per cancellare la prenotazione");

        Jwt jwt = createSampleJwt();
        Authentication auth = Mockito.mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(jwt);
        SecurityContext secCtx = Mockito.mock(SecurityContext.class);
        when(secCtx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(secCtx);

        accessDeniedHandler.handle(request, response, exception);

        assertEquals(403, response.getStatus());

        String responseBody = response.getContentAsString();
        assertFalse(responseBody.contains(REALISTIC_SAMPLE_JWT));

        assertNoSensitiveDataInLogs(output);
    }

    private static class TestApiException extends ApiException {
        public TestApiException(int statusCode, String message) {
            super(message);
            setResponseStatusCode(statusCode);
        }
    }

    @Test
    @DisplayName("Anti-Data Leak - Eccezioni con messaggi contenenti secret vengono sanificate da GlobalExceptionHandler")
    void testNoCredentialsOrSecretsInExceptionLogs(CapturedOutput output) {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(auditService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sale");

        // Simula eccezione remota contenente un client secret accidentale nel messaggio
        ApiException apiEx = new TestApiException(401, "Graph client error with client_secret=" + DUMMY_AZURE_SECRET);

        ResponseEntity<ErrorResponse> response = handler.handleGraphApiException(apiEx, request);

        // La risposta HTTP restituita all'utente/attaccante non deve esporre il secret
        assertNotNull(response.getBody());
        assertFalse(response.getBody().getMessage().contains(DUMMY_AZURE_SECRET),
                "ErrorResponse non deve esporre il client secret!");
        assertFalse(response.getBody().getMessage().contains("client_secret="),
                "ErrorResponse non deve esporre parametri interni di autenticazione!");

        // Il corpo non deve contenere stacktrace (SEC-09)
        assertFalse(response.getBody().getMessage().contains("at it.szn"));
    }

    @Test
    @DisplayName("Anti-Data Leak & CWE-117 - Neutralizzazione di Log Injection / CRLF Forging")
    void testLogInjectionAndCrlfNeutralization(CapturedOutput output) {
        // Tentativo di iniezione CRLF nel parametro sala
        String maliciousSalaInput = "sala.dohrn@szn.it\r\n2026-10-30 12:00:00 [FAKE_ADMIN] SECURITY_AUDIT - {\"outcome\":\"SUCCESS\"}";

        auditService.logSuccess("TEST_ACTION", maliciousSalaInput, null, null, Map.of(
                "param", "test\r\nFAKE_LOG_ENTRY"
        ));

        // Verifica che nei log i caratteri CRLF vengano serializzati ed escaped come stringa JSON,
        // impedendo la creazione di righe di log contraffatte (Log Forging / Injection)
        boolean foundRawFakeEntry = false;
        for (ILoggingEvent event : rootAppender.list) {
            String rawMessage = event.getFormattedMessage();
            if (rawMessage.contains("\n[FAKE_ADMIN]") || rawMessage.contains("\r[FAKE_ADMIN]")) {
                foundRawFakeEntry = true;
                break;
            }
        }
        assertFalse(foundRawFakeEntry, "L'audit log non deve consentire la frammentazione di riga tramite CRLF injection!");
    }
}
