package it.szn.prenotazionesale.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.kiota.ApiException;
import com.microsoft.kiota.ResponseHeaders;
import it.szn.prenotazionesale.config.CustomAccessDeniedHandler;
import it.szn.prenotazionesale.config.CustomAuthenticationEntryPoint;
import it.szn.prenotazionesale.controller.PrenotazioniController;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Test di conformità ai requisiti NIS 2 (Art. 21 - Gestione rischi e
 * tracciamento incidenti di cibersicurezza)
 * e GDPR (Art. 30 e Art. 32 - Accountability, tracciabilità accessi e modifiche
 * dati personali).
 *
 * Verifica che per ogni operazione critica (creazione, modifica, cancellazione,
 * 401 unauthenticated,
 * 403 unauthorized, incidenti cloud Graph) venga emesso un audit trail
 * immutabile contenente:
 * - User Object ID (o identità chiamante)
 * - Timestamp strutturato ISO-8601
 * - Azione eseguita
 * - Risorsa target
 * - Esito dell'operazione (SUCCESS, FAILURE, DENIED/REJECTED)
 */
@ExtendWith(OutputCaptureExtension.class)
class Nis2SecurityAuditTrailTest {

    private Logger auditLogger;
    private ListAppender<ILoggingEvent> listAppender;
    private ObjectMapper objectMapper;
    private SecurityAuditService auditService;
    private PrenotazioniService prenotazioniService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        auditService = new SecurityAuditService();
        prenotazioniService = Mockito.mock(PrenotazioniService.class);

        auditLogger = (Logger) LoggerFactory.getLogger("SECURITY_AUDIT");
        listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        if (auditLogger != null && listAppender != null) {
            auditLogger.detachAppender(listAppender);
            listAppender.stop();
        }
        SecurityContextHolder.clearContext();
    }

    private Jwt createMockJwt(String oid, String upn, List<String> roles) {
        return new Jwt(
                "mock.token.signature",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256", "typ", "JWT"),
                Map.of(
                        "oid", oid,
                        "preferred_username", upn,
                        "roles", roles,
                        "sub", oid));
    }

    @Test
    @DisplayName("NIS 2 / GDPR - Creazione prenotazione genera Security Audit Event completo con esito SUCCESS")
    void testCreateBooking_LogsAuditEventWithMandatoryFields(CapturedOutput output) throws Exception {
        Jwt jwt = createMockJwt("user-admin-uuid-001", "mario.rossi@szn.it", List.of("RoomBooking.Admin"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/prenotazioni");
        request.setRemoteAddr("10.20.30.40");
        PrenotazioniController controller = new PrenotazioniController(prenotazioniService, auditService, request);

        PrenotazioneRequestDTO dto = PrenotazioneRequestDTO.builder()
                .salaEmail("sala.dohrn@szn.it")
                .titolo("Riunione Direttivo")
                .start("2026-11-01T09:00:00")
                .end("2026-11-01T10:00:00")
                .build();

        when(prenotazioniService.creaPrenotazione(any(), any())).thenReturn(
                PrenotazioneDTO.builder()
                        .id("booking-id-12345")
                        .salaEmail("sala.dohrn@szn.it")
                        .titolo("Riunione Direttivo")
                        .start("2026-11-01T09:00:00")
                        .end("2026-11-01T10:00:00")
                        .organizzatoreNome("Mario Rossi")
                        .build());

        controller.creaPrenotazione(dto, jwt);

        assertFalse(listAppender.list.isEmpty(),
                "Un evento di sicurezza deve essere stato emesso nel logger SECURITY_AUDIT");
        ILoggingEvent loggingEvent = listAppender.list.get(listAppender.list.size() - 1);
        assertEquals(Level.INFO, loggingEvent.getLevel());

        JsonNode json = objectMapper.readTree(loggingEvent.getFormattedMessage());

        // 1. User Object ID
        assertEquals("user-admin-uuid-001", json.get("userObjectId").asText());
        assertEquals("mario.rossi@szn.it", json.get("userPrincipalName").asText());

        // 2. Timestamp ISO-8601
        String timestamp = json.get("timestamp").asText();
        assertNotNull(timestamp);
        assertDoesNotThrow(() -> Instant.parse(timestamp), "Il timestamp deve essere in formato ISO-8601 valido");

        // 3. Azione eseguita
        assertEquals("CREATE_BOOKING", json.get("action").asText());
        assertEquals("SECURITY_AUDIT", json.get("eventType").asText());

        // 4. Risorsa target
        assertEquals("sala.dohrn@szn.it", json.get("targetResource").asText());

        // 5. Esito dell'operazione
        assertEquals("SUCCESS", json.get("outcome").asText());

        // 6. Indirizzo IP chiamante e correlationId
        assertEquals("10.20.30.40", json.get("clientIp").asText());
        assertNotNull(json.get("correlationId").asText());

        // 7. Dettagli specifici dell'operazione
        JsonNode details = json.get("details");
        assertNotNull(details);
        assertEquals("booking-id-12345", details.get("bookingId").asText());

        // Verifica cattura su console per integrazione SIEM
        assertTrue(output.getAll().contains("CREATE_BOOKING"));
        assertTrue(output.getAll().contains("booking-id-12345"));
    }

    @Test
    @DisplayName("NIS 2 / GDPR - Modifica prenotazione genera Security Audit Event con eventId target ed esito SUCCESS")
    void testUpdateBooking_LogsAuditEventWithMandatoryFields() throws Exception {
        Jwt jwt = createMockJwt("user-admin-uuid-002", "anna.bianchi@szn.it", List.of("RoomBooking.Admin"));
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/prenotazioni/event-789");
        request.setRemoteAddr("192.168.1.15");
        PrenotazioniController controller = new PrenotazioniController(prenotazioniService, auditService, request);

        PrenotazioneRequestDTO dto = PrenotazioneRequestDTO.builder()
                .salaEmail("sala.actinia@szn.it")
                .titolo("Seminario Biologia Modificato")
                .start("2026-11-02T14:00:00")
                .end("2026-11-02T16:00:00")
                .tipoModifica("SINGOLA")
                .build();

        when(prenotazioniService.modificaPrenotazione(eq("event-789"), any(), any())).thenReturn(
                PrenotazioneDTO.builder()
                        .id("event-789")
                        .salaEmail("sala.actinia@szn.it")
                        .titolo("Seminario Biologia Modificato")
                        .build());

        controller.modificaPrenotazione("event-789", dto, jwt);

        assertFalse(listAppender.list.isEmpty());
        JsonNode json = objectMapper
                .readTree(listAppender.list.get(listAppender.list.size() - 1).getFormattedMessage());

        assertEquals("user-admin-uuid-002", json.get("userObjectId").asText());
        assertEquals("UPDATE_BOOKING", json.get("action").asText());
        assertEquals("event-789", json.get("targetResource").asText());
        assertEquals("SUCCESS", json.get("outcome").asText());
        assertDoesNotThrow(() -> Instant.parse(json.get("timestamp").asText()));
        assertEquals("sala.actinia@szn.it", json.get("details").get("salaEmail").asText());
        assertEquals("SINGOLA", json.get("details").get("tipoModifica").asText());
    }

    @Test
    @DisplayName("NIS 2 / GDPR - Cancellazione prenotazione genera Security Audit Event con eventId ed esito SUCCESS")
    void testDeleteBooking_LogsAuditEventWithMandatoryFields() throws Exception {
        Jwt jwt = createMockJwt("user-admin-uuid-003", "giuseppe.verdi@szn.it", List.of("RoomBooking.Admin"));
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/prenotazioni/event-delete-999");
        request.setRemoteAddr("10.0.0.5");
        PrenotazioniController controller = new PrenotazioniController(prenotazioniService, auditService, request);

        controller.cancellaPrenotazione("event-delete-999", "sala.dohrn@szn.it", "SINGOLA", null, jwt);

        assertFalse(listAppender.list.isEmpty());
        JsonNode json = objectMapper
                .readTree(listAppender.list.get(listAppender.list.size() - 1).getFormattedMessage());

        assertEquals("user-admin-uuid-003", json.get("userObjectId").asText());
        assertEquals("DELETE_BOOKING", json.get("action").asText());
        assertEquals("event-delete-999", json.get("targetResource").asText());
        assertEquals("SUCCESS", json.get("outcome").asText());
        assertDoesNotThrow(() -> Instant.parse(json.get("timestamp").asText()));
        assertEquals("sala.dohrn@szn.it", json.get("details").get("salaEmail").asText());
        assertEquals("SINGOLA", json.get("details").get("tipoCancellazione").asText());
    }

    @Test
    @DisplayName("NIS 2 - Rigetto autenticazione 401 genera evento AUTH_FAILURE con esito FAILURE e ANONYMOUS user")
    void testAuthenticationFailure_401_LogsSecurityAuditEvent() throws Exception {
        CustomAuthenticationEntryPoint entryPoint = new CustomAuthenticationEntryPoint(auditService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/prenotazioni");
        request.setRemoteAddr("172.16.0.99");
        MockHttpServletResponse response = new MockHttpServletResponse();
        BadCredentialsException exception = new BadCredentialsException("Token JWT non valido o scaduto");

        entryPoint.commence(request, response, exception);

        assertEquals(401, response.getStatus());
        assertFalse(listAppender.list.isEmpty());

        JsonNode json = objectMapper
                .readTree(listAppender.list.get(listAppender.list.size() - 1).getFormattedMessage());

        assertEquals("AUTH_FAILURE", json.get("eventType").asText());
        assertEquals("AUTHENTICATE", json.get("action").asText());
        assertEquals("FAILURE", json.get("outcome").asText());
        assertEquals("ANONYMOUS", json.get("userObjectId").asText());
        assertEquals("ANONYMOUS", json.get("userPrincipalName").asText());
        assertEquals("/api/prenotazioni", json.get("targetResource").asText());
        assertEquals("172.16.0.99", json.get("clientIp").asText());
        assertDoesNotThrow(() -> Instant.parse(json.get("timestamp").asText()));
        assertTrue(json.get("details").get("reason").asText().contains("Token JWT non valido"));
    }

    @Test
    @DisplayName("NIS 2 - Rigetto autorizzazione 403 genera evento ACCESS_VIOLATION con esito DENIED e userObjectId")
    void testAccessDenied_403_LogsSecurityAuditEvent() throws Exception {
        CustomAccessDeniedHandler accessDeniedHandler = new CustomAccessDeniedHandler(auditService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/prenotazioni");
        request.setRemoteAddr("192.168.10.25");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AccessDeniedException exception = new AccessDeniedException("Privilegi insufficienti per la prenotazione");

        // Simula utente autenticato ma privo di ruolo RoomBooking.Admin
        Jwt standardUserJwt = createMockJwt("user-standard-oid-555", "utente.base@szn.it", List.of("User.Read"));
        Authentication authentication = Mockito.mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(standardUserJwt);

        SecurityContext securityContext = Mockito.mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        accessDeniedHandler.handle(request, response, exception);

        assertEquals(403, response.getStatus());
        assertFalse(listAppender.list.isEmpty());

        JsonNode json = objectMapper
                .readTree(listAppender.list.get(listAppender.list.size() - 1).getFormattedMessage());

        assertEquals("ACCESS_VIOLATION", json.get("eventType").asText());
        assertEquals("POST /api/prenotazioni", json.get("action").asText());
        // L'esito indica rifiuto/rigetto accesso
        String outcome = json.get("outcome").asText();
        assertTrue("DENIED".equalsIgnoreCase(outcome) || "REJECTED".equalsIgnoreCase(outcome),
                "L'esito deve riflettere il rigetto dell'autorizzazione (DENIED o REJECTED)");
        assertEquals("user-standard-oid-555", json.get("userObjectId").asText());
        assertEquals("utente.base@szn.it", json.get("userPrincipalName").asText());
        assertEquals("User.Read", json.get("userRoles").asText());
        assertEquals("/api/prenotazioni", json.get("targetResource").asText());
        assertEquals("192.168.10.25", json.get("clientIp").asText());
        assertDoesNotThrow(() -> Instant.parse(json.get("timestamp").asText()));
    }

    private static class TestApiException extends ApiException {
        private static final long serialVersionUID = 1L;

        public TestApiException(int statusCode, String message, ResponseHeaders headers) {
            super(message);
            setResponseStatusCode(statusCode);
            if (headers != null) {
                setResponseHeaders(headers);
            }
        }
    }

    @Test
    @DisplayName("NIS 2 Art. 21 - Tracciamento incidenti con Microsoft Graph API come GRAPH_INCIDENT")
    void testGraphIncidentAuditLogging() throws Exception {
        GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler(auditService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/prenotazioni/tutte");

        ResponseHeaders headers = new ResponseHeaders();
        headers.add("Retry-After", "15");
        ApiException throttlingException = new TestApiException(429, "Rate limit exceeded", headers);

        exceptionHandler.handleGraphApiException(throttlingException, request);

        assertFalse(listAppender.list.isEmpty());
        JsonNode json = objectMapper
                .readTree(listAppender.list.get(listAppender.list.size() - 1).getFormattedMessage());

        assertEquals("GRAPH_INCIDENT", json.get("eventType").asText());
        assertEquals("GRAPH_API_CALL", json.get("action").asText());
        assertEquals("FAILURE", json.get("outcome").asText());
        assertEquals("/api/prenotazioni/tutte", json.get("targetResource").asText());
        assertNotNull(json.get("correlationId").asText());
        assertEquals("RATE_LIMIT_EXCEEDED", json.get("details").get("incidentType").asText());
        assertEquals(429, json.get("details").get("graphStatusCode").asInt());
    }

    @Test
    @DisplayName("GDPR / NIS 2 - Estrazione sicura Client IP gestisce catena di proxy X-Forwarded-For")
    void testClientIpExtraction_HandlesProxyChainSafely() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.195, 70.41.3.18, 150.172.238.178");
        request.setRemoteAddr("127.0.0.1");

        String clientIp = auditService.extractClientIp(request);
        assertEquals("203.0.113.195", clientIp, "Deve estrarre il primo IP della catena (client originale)");

        // Test fallback con request nulla o header assente
        assertEquals("UNKNOWN", auditService.extractClientIp(null));

        MockHttpServletRequest directRequest = new MockHttpServletRequest();
        directRequest.setRemoteAddr("192.168.1.55");
        assertEquals("192.168.1.55", auditService.extractClientIp(directRequest));
    }
}
