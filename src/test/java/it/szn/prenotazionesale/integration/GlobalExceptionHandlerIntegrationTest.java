package it.szn.prenotazionesale.integration;

import com.microsoft.kiota.ApiException;
import com.microsoft.kiota.ResponseHeaders;
import it.szn.prenotazionesale.audit.SecurityAuditService;
import it.szn.prenotazionesale.config.AppConfig;
import it.szn.prenotazionesale.exception.GraphPaginationException;
import it.szn.prenotazionesale.service.PrenotazioniService;
import it.szn.prenotazionesale.service.SaleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration Test per il GlobalExceptionHandler e la prevenzione di
 * Information Disclosure (SEC-09):
 * 1. Verifica che in caso di eccezioni applicative (IllegalArgumentException,
 * MethodArgumentNotValidException,
 * IllegalStateException, AccessDeniedException) il payload di errore sia
 * sanificato.
 * 2. Verifica che in caso di errori Microsoft Graph (ApiException 429, 401,
 * 503) nessun messaggio diagnostico
 * interno, URL grezzo Microsoft Graph o credenziale/secret trapeli nel corpo
 * JSON.
 * 3. Verifica che in caso di CompletionException con TimeoutException il client
 * riceva 504 senza dettagli dei thread interni.
 * 4. Verifica che in caso di errore interno non gestito (500) non compaiano MAI
 * stacktrace Java, nomi di classi interne,
 * strutture package o file .java.
 */
@SpringBootTest
class GlobalExceptionHandlerIntegrationTest {

        @Autowired
        private WebApplicationContext webApplicationContext;

        private MockMvc mockMvc;

        @Autowired
        private AppConfig appConfig;

        @MockitoBean
        private JwtDecoder jwtDecoder;

        @MockitoSpyBean
        private SecurityAuditService auditService;

        @MockitoBean
        private SaleService saleService;

        @MockitoBean
        private PrenotazioniService prenotazioniService;

        private String validAdminToken;
        private String validUserToken;

        private static class MockApiException extends ApiException {
                private static final long serialVersionUID = 1L;

                public MockApiException(int statusCode, String message, ResponseHeaders headers) {
                        super(message);
                        setResponseStatusCode(statusCode);
                        setResponseHeaders(headers);
                }
        }

        @BeforeEach
        void setUp() {
                mockMvc = MockMvcBuilders
                                .webAppContextSetup(webApplicationContext)
                                .apply(springSecurity())
                                .build();

                validAdminToken = "valid.admin.token";
                Jwt adminJwt = new Jwt(
                                validAdminToken,
                                Instant.now(),
                                Instant.now().plusSeconds(3600),
                                Map.of("alg", "RS256"),
                                Map.of(
                                                "sub", "admin-1",
                                                "oid", "admin-oid-1",
                                                "iss",
                                                "https://sts.windows.net/" + appConfig.getAzure().getTenantId() + "/",
                                                "aud", List.of(appConfig.getAzure().getClientId()),
                                                "roles", List.of("RoomBooking.Admin")));
                when(jwtDecoder.decode(validAdminToken)).thenReturn(adminJwt);

                validUserToken = "valid.user.token";
                Jwt userJwt = new Jwt(
                                validUserToken,
                                Instant.now(),
                                Instant.now().plusSeconds(3600),
                                Map.of("alg", "RS256"),
                                Map.of(
                                                "sub", "user-1",
                                                "oid", "user-oid-1",
                                                "iss",
                                                "https://sts.windows.net/" + appConfig.getAzure().getTenantId() + "/",
                                                "aud", List.of(appConfig.getAzure().getClientId()),
                                                "roles", List.of("RoomBooking.User")));
                when(jwtDecoder.decode(validUserToken)).thenReturn(userJwt);
        }

        // =========================================================================
        // 1. ILLEGAL ARGUMENT & VALIDATION EXCEPTIONS
        // =========================================================================

        @Test
        @DisplayName("IllegalArgumentException sollevata dal Service deve restituire 400 Bad Request senza stacktrace o package")
        void testIllegalArgumentException_Returns400WithoutInternalLeak() throws Exception {
                when(saleService.getSale())
                                .thenThrow(new IllegalArgumentException(
                                                "Parametro di richiesta non conforme ai requisiti"));

                MvcResult result = mockMvc.perform(get("/api/sale")
                                .header("Authorization", "Bearer " + validAdminToken))
                                .andExpect(status().isBadRequest())
                                .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
                                .andExpect(jsonPath("$.status").value(400))
                                .andExpect(jsonPath("$.title").value("Bad Request"))
                                .andExpect(jsonPath("$.detail")
                                                .value("Parametro di richiesta non conforme ai requisiti"))
                                .andReturn();

                assertNoInformationDisclosure(result.getResponse().getContentAsString());
        }

        @Test
        @DisplayName("MethodArgumentNotValidException su POST con body invalido deve restituire 400 controllato")
        void testMethodArgumentNotValidException_Returns400WithoutStackTrace() throws Exception {
                // Invio payload JSON vuoto che viola le annotazioni @NotBlank / @NotNull del
                // DTO
                MvcResult result = mockMvc.perform(post("/api/prenotazioni")
                                .header("Authorization", "Bearer " + validAdminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status").value(400))
                                .andReturn();

                assertNoInformationDisclosure(result.getResponse().getContentAsString());
        }

        @Test
        @DisplayName("IllegalStateException per conflitto di stato deve restituire 409 Conflict senza package interni")
        void testIllegalStateException_Returns409WithoutPackageLeak() throws Exception {
                when(saleService.getSale())
                                .thenThrow(new IllegalStateException(
                                                "Conflitto di sincronizzazione del calendario sala"));

                MvcResult result = mockMvc.perform(get("/api/sale")
                                .header("Authorization", "Bearer " + validAdminToken))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.status").value(409))
                                .andExpect(jsonPath("$.title").value("Conflict"))
                                .andExpect(jsonPath("$.detail")
                                                .value("Conflitto di sincronizzazione del calendario sala"))
                                .andReturn();

                assertNoInformationDisclosure(result.getResponse().getContentAsString());
        }

        @Test
        @DisplayName("GraphPaginationException deve restituire 502 Bad Gateway con messaggio generico senza email e loggare audit")
        void testGraphPaginationException_Returns502WithoutRoomEmailAndAuditsIncident() throws Exception {
                when(saleService.getSale())
                                .thenThrow(new GraphPaginationException(
                                                "Superato il limite massimo di pagine (50) per la lettura del calendario della sala sala-segreta@szn.it"));

                MvcResult result = mockMvc.perform(get("/api/sale")
                                .header("Authorization", "Bearer " + validAdminToken))
                                .andExpect(status().isBadGateway())
                                .andExpect(jsonPath("$.status").value(502))
                                .andExpect(jsonPath("$.title").value("Bad Gateway"))
                                .andExpect(jsonPath("$.message")
                                                .value("Impossibile leggere il calendario della sala al momento. Riprova più tardi."))
                                .andReturn();

                String body = result.getResponse().getContentAsString();
                assertNoInformationDisclosure(body);
                assertFalse(body.contains("sala-segreta@szn.it"), "L'email della sala non deve trapelare nel body");
                assertFalse(body.contains("pagine"), "Il conteggio delle pagine non deve trapelare nel body");

                verify(auditService).logGraphIncident(
                                eq("GRAPH_PAGINATION_LIMIT"),
                                eq("/api/sale"),
                                eq(502),
                                org.mockito.ArgumentMatchers.contains("sala-segreta@szn.it"),
                                anyString());
        }

        // =========================================================================
        // 2. MICROSOFT GRAPH API EXCEPTIONS (Kiota ApiException)
        // =========================================================================

        @Test
        @DisplayName("ApiException 429 da Microsoft Graph deve restituire 429 con header Retry-After senza esporre URL o diagnostica grezza")
        void testGraphApiException_429Throttling_ReturnsClean429WithRetryAfter() throws Exception {
                ResponseHeaders headers = new ResponseHeaders();
                headers.add("Retry-After", "45");
                ApiException graphThrottling = new MockApiException(
                                429,
                                "ActivityLimitReached: Client throttled by Graph API https://graph.microsoft.com/v1.0/users/room1/calendarView",
                                headers);
                when(saleService.getSale()).thenThrow(graphThrottling);

                MvcResult result = mockMvc.perform(get("/api/sale")
                                .header("Authorization", "Bearer " + validAdminToken))
                                .andExpect(status().isTooManyRequests())
                                .andExpect(header().string("Retry-After", "45"))
                                .andExpect(jsonPath("$.status").value(429))
                                .andExpect(jsonPath("$.message").value(containsString("45 secondi")))
                                .andReturn();

                String body = result.getResponse().getContentAsString();
                assertNoInformationDisclosure(body);
                assertFalse(body.contains("ActivityLimitReached"),
                                "Il codice errore grezzo di Graph non deve trapelare");
                assertFalse(body.contains("https://graph.microsoft.com"),
                                "Gli endpoint URL interni di Graph non devono trapelare");
        }

        @Test
        @DisplayName("ApiException 401/403 da Graph (credenziali scadute) deve restituire 502 Bad Gateway con Correlation ID e nessun secret")
        void testGraphApiException_AuthFailure_Returns502WithCorrelationId() throws Exception {
                ApiException graphAuthError = new MockApiException(
                                401,
                                "InvalidAuthenticationToken: Lifetime validation failed. Secret client_secret_value_xyz expired.",
                                new ResponseHeaders());
                when(saleService.getSale()).thenThrow(graphAuthError);

                MvcResult result = mockMvc.perform(get("/api/sale")
                                .header("Authorization", "Bearer " + validAdminToken))
                                .andExpect(status().isBadGateway())
                                .andExpect(jsonPath("$.status").value(502))
                                .andExpect(jsonPath("$.title").value("Bad Gateway"))
                                .andExpect(jsonPath("$.message").value(containsString("Rif: ")))
                                .andReturn();

                String body = result.getResponse().getContentAsString();
                assertNoInformationDisclosure(body);
                assertFalse(body.contains("client_secret"),
                                "I secret o dettagli credenziali non devono MAI comparire nel body");
                assertFalse(body.contains("InvalidAuthenticationToken"),
                                "I messaggi interni di Entra ID non devono trapelare");
        }

        @Test
        @DisplayName("ApiException 503 remota da Graph deve restituire 502 Bad Gateway con messaggio sanificato")
        void testGraphApiException_RemoteServerError_ReturnsClean502() throws Exception {
                ApiException graph503 = new MockApiException(
                                503,
                                "Mailbox database offline on cluster ams-prd-exch-01.office365.com",
                                new ResponseHeaders());
                when(saleService.getSale()).thenThrow(graph503);

                MvcResult result = mockMvc.perform(get("/api/sale")
                                .header("Authorization", "Bearer " + validAdminToken))
                                .andExpect(status().isBadGateway())
                                .andExpect(jsonPath("$.status").value(502))
                                .andExpect(jsonPath("$.message")
                                                .value(containsString("servizio di calendario esterno Microsoft 365")))
                                .andReturn();

                String body = result.getResponse().getContentAsString();
                assertNoInformationDisclosure(body);
                assertFalse(body.contains("ams-prd-exch-01"), "Hostname interni del cloud non devono trapelare");
        }

        // =========================================================================
        // 3. COMPLETION EXCEPTION & TIMEOUTS
        // =========================================================================

        @Test
        @DisplayName("CompletionException con TimeoutException interna deve restituire 504 Gateway Timeout")
        void testCompletionException_Timeout_Returns504() throws Exception {
                TimeoutException timeoutEx = new TimeoutException(
                                "ThreadPoolTaskExecutor task timed out on thread pool-worker-7");
                when(saleService.getSale()).thenThrow(new CompletionException(timeoutEx));

                MvcResult result = mockMvc.perform(get("/api/sale")
                                .header("Authorization", "Bearer " + validAdminToken))
                                .andExpect(status().isGatewayTimeout())
                                .andExpect(jsonPath("$.status").value(504))
                                .andExpect(jsonPath("$.title").value("Gateway Timeout"))
                                .andExpect(jsonPath("$.message").value(containsString("scaduta")))
                                .andReturn();

                String body = result.getResponse().getContentAsString();
                assertNoInformationDisclosure(body);
                assertFalse(body.contains("ThreadPoolTaskExecutor"),
                                "Dettagli dei thread pool Java non devono trapelare");
                assertFalse(body.contains("pool-worker-7"), "Nomi dei thread non devono trapelare");
        }

        // =========================================================================
        // 4. ECCEZIONI GENERICHE NON GESTITE (500) E SICUREZZA RUOLI (403)
        // =========================================================================

        @Test
        @DisplayName("Eccezione non gestita (NullPointerException) deve restituire 500 generico SENZA trace Java")
        void testGenericUnhandledException_Returns500WithoutJavaStackTrace() throws Exception {
                when(saleService.getSale())
                                .thenThrow(new NullPointerException(
                                                "NullPointer in internal business logic at SaleService.java:77"));

                MvcResult result = mockMvc.perform(get("/api/sale")
                                .header("Authorization", "Bearer " + validAdminToken))
                                .andExpect(status().isInternalServerError())
                                .andExpect(jsonPath("$.status").value(500))
                                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                                .andExpect(jsonPath("$.message")
                                                .value("Si è verificato un errore interno. Riprova più tardi."))
                                .andReturn();

                String body = result.getResponse().getContentAsString();
                assertNoInformationDisclosure(body);
                assertFalse(body.contains("NullPointerException"), "Il tipo di eccezione Java non deve comparire");
                assertFalse(body.contains("SaleService.java"), "Nomi dei file sorgente Java non devono comparire");
                assertFalse(body.contains(":77"), "Numeri di riga non devono comparire");
        }

        @Test
        @DisplayName("Utente non autorizzato (senza ruolo Admin) riceve 403 Forbidden senza trapelare classi interne Spring")
        void testAccessDeniedException_Returns403WithoutInternalClassNames() throws Exception {
                MvcResult result = mockMvc.perform(post("/api/prenotazioni")
                                .header("Authorization", "Bearer " + validUserToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"salaEmail\":\"sala1@szn.it\",\"titolo\":\"Test Non-Admin\"}"))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.status").value(403))
                                .andExpect(jsonPath("$.title").value("Forbidden"))
                                .andReturn();

                String body = result.getResponse().getContentAsString();
                assertNoInformationDisclosure(body);
                assertFalse(body.contains("AccessDeniedException"),
                                "Nomi di classi Spring Security non devono trapelare");
        }

        // =========================================================================
        // 5. DEFAULT TIPO CANCELLAZIONE (DELETE /api/prenotazioni)
        // =========================================================================

        @Test
        @DisplayName("DELETE senza tipoCancellazione deve assumere di default 'SINGOLA' sia verso il service che nell'audit")
        void testDeletePrenotazione_DefaultTipoCancellazione_IsSingola() throws Exception {
                mockMvc.perform(delete("/api/prenotazioni/event-default-1")
                                .header("Authorization", "Bearer " + validAdminToken)
                                .param("salaEmail", "sala1@szn.it"))
                                .andExpect(status().isNoContent());

                verify(prenotazioniService).cancellaPrenotazione(
                                eq("event-default-1"),
                                eq("sala1@szn.it"),
                                eq("SINGOLA"),
                                isNull(),
                                any(Jwt.class));

                verify(auditService).logSuccess(
                                eq("DELETE_BOOKING"),
                                eq("event-default-1"),
                                any(Jwt.class),
                                any(),
                                argThat(details -> "SINGOLA".equals(details.get("tipoCancellazione"))));
        }

        @Test
        @DisplayName("DELETE con tipoCancellazione=SERIE esplicito deve propagare 'SERIE' sia verso il service che nell'audit")
        void testDeletePrenotazione_ExplicitSerie_PropagatesSerie() throws Exception {
                mockMvc.perform(delete("/api/prenotazioni/event-serie-1")
                                .header("Authorization", "Bearer " + validAdminToken)
                                .param("salaEmail", "sala1@szn.it")
                                .param("tipoCancellazione", "SERIE"))
                                .andExpect(status().isNoContent());

                verify(prenotazioniService).cancellaPrenotazione(
                                eq("event-serie-1"),
                                eq("sala1@szn.it"),
                                eq("SERIE"),
                                isNull(),
                                any(Jwt.class));

                verify(auditService).logSuccess(
                                eq("DELETE_BOOKING"),
                                eq("event-serie-1"),
                                any(Jwt.class),
                                any(),
                                argThat(details -> "SERIE".equals(details.get("tipoCancellazione"))));
        }

        // =========================================================================
        // METODO DI CONTROLLO GENERALE ANTI-INFORMATION DISCLOSURE
        // =========================================================================

        private void assertNoInformationDisclosure(String responseBody) {
                assertNotNull(responseBody, "Il body di risposta non deve essere null");

                // 1. Assenza di stacktrace
                assertFalse(responseBody.contains("stackTrace"), "Non deve comparire il campo 'stackTrace'");
                assertFalse(responseBody.contains("trace"), "Non deve comparire il campo 'trace'");
                assertFalse(responseBody.contains("at it.szn."),
                                "Non devono comparire frame di stacktrace 'at it.szn.'");
                assertFalse(responseBody.contains("at org.springframework."),
                                "Non devono comparire frame 'at org.springframework.'");
                assertFalse(responseBody.contains("at java.base"), "Non devono comparire frame 'at java.base'");

                // 2. Assenza di package o classi interne
                assertFalse(responseBody.contains("it.szn.prenotazionesale"),
                                "Non deve comparire il package applicativo 'it.szn.prenotazionesale'");
                assertFalse(responseBody.contains("java.lang."), "Non devono comparire classi del package java.lang.");
                assertFalse(responseBody.contains("com.microsoft.kiota"), "Non devono comparire classi Kiota");
                assertFalse(responseBody.contains("com.microsoft.graph"), "Non devono comparire classi Graph");

                // 3. Assenza di riferimenti a file sorgente compilati
                assertFalse(responseBody.contains(".java:"),
                                "Non devono comparire riferimenti a righe di codice file .java:");
        }
}
