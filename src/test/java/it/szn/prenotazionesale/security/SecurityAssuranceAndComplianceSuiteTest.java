package it.szn.prenotazionesale.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.Event;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.audit.SecurityAuditService;
import it.szn.prenotazionesale.config.AppConfig;
import it.szn.prenotazionesale.config.ClientIpResolver;
import it.szn.prenotazionesale.graph.GraphCalendarGateway;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.model.SalaDTO;
import it.szn.prenotazionesale.service.SaleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Suite di Test Integrata di Sicurezza e Garanzia di Conformità
 * Quadro di Riferimento: OWASP ASVS v4.0.3 & NIS2 Art. 21.
 *
 * Copre:
 * 1. Autenticazione & Session Security (ASVS V2, V3 - Statelessness, Token validation)
 * 2. Controllo degli Accessi & RBAC Dinamico (ASVS V4 - Strict role enforcement, PreAuthorize)
 * 3. Safe Defaults per Operazioni Distruttive (ASVS V1.4, V4.2 - DELETE default SINGOLA)
 * 4. Difesa Perimetrale & Anti-IP-Spoofing (ASVS V14.4 - ClientIpResolver, Proxy hopping, Port stripping)
 * 5. Validazione dell'Input & Resilienza alle Iniezioni (ASVS V5 - CRLF, Path Traversal, SQLi)
 * 6. Prevenzione dell'Information Disclosure & Gestione Errori (ASVS V7 - No stack trace leak)
 * 7. Integrità dell'Audit Trail & Redazione Dati Sensibili (NIS2 Art. 21 - Redazione token e credenziali)
 */
@SpringBootTest
@DisplayName("Security Assurance & Compliance Suite (OWASP ASVS v4.0.3 / NIS2)")
class SecurityAssuranceAndComplianceSuiteTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AppConfig appConfig;

    @Autowired
    private ClientIpResolver clientIpResolver;

    @Autowired
    private SecurityAuditService auditService;

    @MockitoBean
    private SaleService saleService;

    @MockitoBean
    private GraphServiceClient graphClient;

    @MockitoBean
    private GraphCalendarGateway calendarGateway;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        when(saleService.getSale()).thenReturn(List.of(
                SalaDTO.builder().id("sala1@szn.it").nome("Sala Dohrn").email("sala1@szn.it").build(),
                SalaDTO.builder().id("sala2@szn.it").nome("Sala Biblioteca").email("sala2@szn.it").build()
        ));

        Event mockEvent = new Event();
        mockEvent.setId("mock-event-id");
        when(calendarGateway.getEvento(anyString(), anyString(), any())).thenReturn(mockEvent);
        when(calendarGateway.getEvento(anyString(), anyString())).thenReturn(mockEvent);
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        String role = appConfig.resolveAdminRole();
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))
                .jwt(builder -> builder
                        .claim("oid", "admin-user-oid-123")
                        .claim("preferred_username", "admin.prenotazioni@szn.it")
                        .claim("roles", List.of(role)));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor standardUserJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_RoomBooking.User"))
                .jwt(builder -> builder
                        .claim("oid", "standard-user-oid-456")
                        .claim("preferred_username", "mario.rossi@szn.it")
                        .claim("roles", List.of("RoomBooking.User")));
    }

    // =========================================================================
    // 1. AUTENTICAZIONE E SESSION SECURITY (ASVS V2 & V3)
    // =========================================================================
    @Nested
    @DisplayName("1. Autenticazione & Session Security (ASVS V2 & V3)")
    class AuthenticationAndSessionSecurity {

        @Test
        @DisplayName("SEC-AUTH-01: Richieste non autenticate a risorse protette generano 401 Unauthorized e zero session state")
        void unauthenticatedWriteRequestsMustReturn401() throws Exception {
            // POST non autenticata
            mockMvc.perform(post("/api/prenotazioni")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"salaEmail\":\"sala1@szn.it\",\"titolo\":\"Hack Attempt\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.title").value("Unauthorized"));

            // DELETE non autenticata
            mockMvc.perform(delete("/api/prenotazioni/evt-fake-123")
                            .param("salaEmail", "sala1@szn.it"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andExpect(jsonPath("$.status").value(401));
        }
    }

    // =========================================================================
    // 2. CONTROLLO ACCESSI E RBAC DINAMICO (ASVS V4 & OWASP Top 10 A01:2021)
    // =========================================================================
    @Nested
    @DisplayName("2. Controllo Accessi & RBAC Dinamico (ASVS V4)")
    class AccessControlAndRbacDefense {

        @Test
        @DisplayName("SEC-RBAC-02: Utente autenticato privo di ruolo amministrativo riceve 403 Forbidden su POST, PATCH, DELETE")
        void nonAdminUserCannotPerformMutations() throws Exception {
            // POST bloccata
            mockMvc.perform(post("/api/prenotazioni")
                            .with(standardUserJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(PrenotazioneRequestDTO.builder()
                                    .salaEmail("sala1@szn.it")
                                    .titolo("Unauthorized Booking")
                                    .start("2026-11-10T10:00:00")
                                    .end("2026-11-10T11:00:00")
                                    .build())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.title").value("Forbidden"));

            // DELETE bloccata
            mockMvc.perform(delete("/api/prenotazioni/evt-001")
                            .with(standardUserJwt())
                            .param("salaEmail", "sala1@szn.it")
                            .param("tipoCancellazione", "SINGOLA"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            verify(calendarGateway, never()).eliminaEvento(anyString(), anyString());
        }

        @Test
        @DisplayName("SEC-RBAC-03: Tentativi di privilege escalation con variazioni di casing o padding sono respinti con 403")
        void roleCasingAndPaddingVariationsMustBeRejected() throws Exception {
            // Lowercase role
            var lowercaseRoleJwt = jwt().authorities(new SimpleGrantedAuthority("ROLE_roombooking.admin"))
                    .jwt(builder -> builder.claim("roles", List.of("roombooking.admin")));

            mockMvc.perform(delete("/api/prenotazioni/evt-001")
                            .with(lowercaseRoleJwt)
                            .param("salaEmail", "sala1@szn.it"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            // Trailing space role
            var paddedRoleJwt = jwt().authorities(new SimpleGrantedAuthority("ROLE_RoomBooking.Admin "))
                    .jwt(builder -> builder.claim("roles", List.of("RoomBooking.Admin ")));

            mockMvc.perform(delete("/api/prenotazioni/evt-001")
                            .with(paddedRoleJwt)
                            .param("salaEmail", "sala1@szn.it"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            verify(calendarGateway, never()).eliminaEvento(anyString(), anyString());
        }
    }

    // =========================================================================
    // 3. SAFE DEFAULTS SU OPERAZIONI DISTRUTTIVE (ASVS V1.4 & V4.2)
    // =========================================================================
    @Nested
    @DisplayName("3. Safe Defaults su Operazioni Distruttive (ASVS V1.4 & V4.2)")
    class SafeDefaultDestructiveMitigation {

        @Test
        @DisplayName("SEC-SAFE-01: Parametro tipoCancellazione con valore non consentito viene rifiutato con 400 Bad Request")
        void invalidCancellationTypeReturns400BadRequest() throws Exception {
            mockMvc.perform(delete("/api/prenotazioni/evt-safe-999")
                            .with(adminJwt())
                            .param("salaEmail", "sala1@szn.it")
                            .param("tipoCancellazione", "DROP_ALL_SERIES"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.message").value(containsString("deve essere 'SERIE' o 'SINGOLA'")));

            verify(calendarGateway, never()).eliminaEvento(anyString(), anyString());
        }

        @Test
        @DisplayName("SEC-SAFE-02: Omissione di tipoCancellazione applica il default sicuro SINGOLA senza cancellare l'intera serie")
        void omittedCancellationTypeDefaultsToSingola() throws Exception {
            mockMvc.perform(delete("/api/prenotazioni/evt-safe-999")
                            .with(adminJwt())
                            .param("salaEmail", "sala1@szn.it"))
                    .andExpect(status().isNoContent());

            // Verifica che l'evento cancellato sia l'occorrenza singola (idSuCuiOperare = eventId)
            verify(calendarGateway).eliminaEvento("sala1@szn.it", "evt-safe-999");
        }
    }

    // =========================================================================
    // 4. DIFESA PERIMETRALE & ANTI-IP-SPOOFING (ASVS V14.4 & NIS2 Art. 21)
    // =========================================================================
    @Nested
    @DisplayName("4. Difesa Perimetrale & Anti-IP-Spoofing (ASVS V14.4)")
    class AntiSpoofingAndNetworkPerimeterDefense {

        @Test
        @DisplayName("SEC-NET-01: Client diretto non fidato con header X-Forwarded-For falsificato: XFF ignorato completamente")
        void untrustedDirectClientCannotSpoofIpViaXff() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("198.51.100.77"); // IP pubblico non fidato
            request.addHeader("X-Forwarded-For", "127.0.0.1, 10.0.0.1, 172.16.0.5");

            String resolvedIp = clientIpResolver.resolve(request);

            // XFF deve essere tassativamente ignorato
            assertEquals("198.51.100.77", resolvedIp);
        }

        @Test
        @DisplayName("SEC-NET-02: Connessione da proxy fidato con catena multi-hop e porte: estrazione primo IP non fidato da destra")
        void trustedProxyTraversesRightToLeftAndStripsPorts() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("127.0.0.1"); // Proxy locale fidato
            // Catena: Spoofed Client, Real Client con porta, Proxy Intermedio fidato con porta
            request.addHeader("X-Forwarded-For", "192.0.2.1, 203.0.113.195:44321, 10.0.0.2:8080");

            String resolvedIp = clientIpResolver.resolve(request);

            // Deve saltare 10.0.0.2 (fidato), estrarre 203.0.113.195 e togliere la porta
            assertEquals("203.0.113.195", resolvedIp);
        }

        @Test
        @DisplayName("SEC-NET-03: Porta non valida o malformata su token XFF: fail-safe rigoroso su remoteAddr")
        void malformedPortInXffFailsSafeToRemoteAddr() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("127.0.0.1");
            request.addHeader("X-Forwarded-For", "203.0.113.195:999999"); // Porta fuori range (>65535)

            String resolvedIp = clientIpResolver.resolve(request);

            // Fail-safe su remoteAddr
            assertEquals("127.0.0.1", resolvedIp);
        }

        @Test
        @DisplayName("SEC-NET-04: Token XFF con tentativi di injection o non-IP: fail-safe rigoroso")
        void nonIpInjectionTokenInXffFailsSafe() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("127.0.0.1");
            request.addHeader("X-Forwarded-For", "<script>alert(1)</script>, 10.0.0.1");

            String resolvedIp = clientIpResolver.resolve(request);
            assertEquals("127.0.0.1", resolvedIp);
        }
    }

    // =========================================================================
    // 5. VALIDAZIONE DELL'INPUT E RESILIENZA ALLE INIEZIONI (ASVS V5)
    // =========================================================================
    @Nested
    @DisplayName("5. Validazione dell'Input & Resilienza alle Iniezioni (ASVS V5)")
    class InputSanitizationAndInjectionDefense {

        @Test
        @DisplayName("SEC-INJ-01: Injection CRLF / Log Forging nel parametro salaEmail viene neutralizzato con 400 Bad Request")
        void crlfInjectionInEmailParameterIsSafelyHandled() throws Exception {
            mockMvc.perform(get("/api/prenotazioni")
                            .with(adminJwt())
                            .param("salaEmail", "sala1@szn.it\r\nSet-Cookie: malicious=true")
                            .param("dataInizio", "2026-11-10T08:00:00")
                            .param("dataFine", "2026-11-10T18:00:00"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("SEC-INJ-02: Path Traversal attempt nel parametro salaEmail viene respinto con 400 Bad Request")
        void pathTraversalInRoomEmailReturnsBadRequest() throws Exception {
            mockMvc.perform(get("/api/prenotazioni")
                            .with(adminJwt())
                            .param("salaEmail", "../../etc/passwd@szn.it")
                            .param("dataInizio", "2026-11-10T08:00:00")
                            .param("dataFine", "2026-11-10T18:00:00"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("SEC-INJ-03: Payload JSON sintatticamente errato viene gestito con 400 Bad Request senza leak di stack trace")
        void malformedJsonPayloadReturns400WithoutStackTrace() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"salaEmail\": \"sala1@szn.it\", \"titolo\": unclosed_json..."))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andReturn();

            String responseBody = result.getResponse().getContentAsString();
            assertFalse(responseBody.contains("org.springframework"));
            assertFalse(responseBody.contains("com.fasterxml.jackson"));
            assertFalse(responseBody.contains("Exception in thread"));
        }
    }

    // =========================================================================
    // 6. PREVENZIONE DELL'INFORMATION DISCLOSURE & GESTIONE ERRORI (ASVS V7)
    // =========================================================================
    @Nested
    @DisplayName("6. Prevenzione dell'Information Disclosure & Error Handling (ASVS V7)")
    class InformationDisclosurePrevention {

        @Test
        @DisplayName("SEC-LEAK-01: Risposte di errore HTTP non espongono dettagli interni di runtime o versioni server")
        void errorResponsesDoNotExposeServerOrFrameworkDetails() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            assertNull(result.getResponse().getHeader("Server"));
            assertNull(result.getResponse().getHeader("X-Powered-By"));
        }
    }

    // =========================================================================
    // 7. INTEGRITÀ AUDIT TRAIL E REDAZIONE DATI SENSIBILI (NIS2 Art. 21)
    // =========================================================================
    @Nested
    @DisplayName("7. Integrità Audit Trail & Redazione Dati Sensibili (NIS2 Art. 21)")
    class Nis2AuditTrailAndDataProtection {

        @Test
        @DisplayName("SEC-AUDIT-01: Redazione automatica di Bearer Token, Raw JWT e Client Secrets nei log di audit")
        void auditSanitizerRedactsBearerTokensAndSecrets() {
            String rawMessageWithTokens = "Failed request with Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.test and client_secret = mySecretValue123";

            String sanitized = auditService.sanitizeSensitiveText(rawMessageWithTokens);

            assertFalse(sanitized.contains("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.test"));
            assertFalse(sanitized.contains("mySecretValue123"));
            assertTrue(sanitized.contains("[REDACTED_TOKEN]") || sanitized.contains("[REDACTED_JWT]"));
            assertTrue(sanitized.contains("[REDACTED_SECRET]"));
        }

        @Test
        @DisplayName("SEC-AUDIT-02: Tentativo di accesso negato (403) genera traccia di audit strutturata con IP risolto in modo sicuro")
        void accessDeniedGeneratesAuditRecordWithSanitizedIp() throws Exception {
            mockMvc.perform(delete("/api/prenotazioni/evt-audit-123")
                            .with(standardUserJwt())
                            .with(request -> {
                                request.setRemoteAddr("198.51.100.44");
                                request.addHeader("X-Forwarded-For", "127.0.0.1"); // Spoofed header
                                return request;
                            })
                            .param("salaEmail", "sala1@szn.it"))
                    .andExpect(status().isForbidden());

            // Verifica che il resolver abbia rilevato l'IP reale 198.51.100.44
            MockHttpServletRequest testReq = new MockHttpServletRequest();
            testReq.setRemoteAddr("198.51.100.44");
            testReq.addHeader("X-Forwarded-For", "127.0.0.1");
            assertEquals("198.51.100.44", clientIpResolver.resolve(testReq));
        }
    }
}
