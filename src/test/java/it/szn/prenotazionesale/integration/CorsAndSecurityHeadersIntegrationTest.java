package it.szn.prenotazionesale.integration;

import it.szn.prenotazionesale.config.AppConfig;
import it.szn.prenotazionesale.model.SalaDTO;
import it.szn.prenotazionesale.service.PrenotazioniService;
import it.szn.prenotazionesale.service.SaleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration Test per la configurazione CORS e gli HTTP Security Headers:
 * 1. Richieste pre-flight OPTIONS con origini autorizzate (whitelist esplicita,
 * Access-Control-Allow-*).
 * 2. Richieste pre-flight OPTIONS con origini non autorizzate (rigetto 403 o
 * assenza header CORS).
 * 3. Richieste effettive da origini autorizzate e non autorizzate.
 * 4. Presenza degli header di sicurezza difensivi (X-Content-Type-Options,
 * X-Frame-Options, CSP, Referrer-Policy).
 * 5. Assenza di header con Information Disclosure o leak di versioni (Server,
 * X-Powered-By).
 * 6. Presenza degli header di sicurezza difensivi anche nelle risposte di
 * errore (401, 403).
 */
@SpringBootTest
class CorsAndSecurityHeadersIntegrationTest {

        @Autowired
        private WebApplicationContext webApplicationContext;

        private MockMvc mockMvc;

        @Autowired
        private AppConfig appConfig;

        @MockitoBean
        private JwtDecoder jwtDecoder;

        @MockitoBean
        private SaleService saleService;

        @MockitoBean
        private PrenotazioniService prenotazioniService;

        private static final String AUTHORIZED_ORIGIN = "http://localhost:5173";
        private static final String UNAUTHORIZED_ORIGIN = "https://evil-attacker.com";
        private static final String ROGUE_ORIGIN = "http://rogue-domain.org";

        @BeforeEach
        void setUp() {
                mockMvc = MockMvcBuilders
                                .webAppContextSetup(webApplicationContext)
                                .apply(springSecurity())
                                .build();
        }

        private String mockValidAdminJwt() {
                String tokenStr = "valid.admin.jwt.token";
                Jwt jwt = new Jwt(
                                tokenStr,
                                Instant.now(),
                                Instant.now().plusSeconds(3600),
                                Map.of("alg", "RS256", "typ", "JWT"),
                                Map.of(
                                                "sub", "admin-1",
                                                "oid", "admin-oid-1",
                                                "iss",
                                                "https://sts.windows.net/" + appConfig.getAzure().getTenantId() + "/",
                                                "aud", List.of(appConfig.getAzure().getClientId()),
                                                "roles", List.of("RoomBooking.Admin")));
                when(jwtDecoder.decode(tokenStr)).thenReturn(jwt);
                return tokenStr;
        }

        // =========================================================================
        // 1. TEST PRE-FLIGHT CORS (OPTIONS)
        // =========================================================================

        @Test
        @DisplayName("Pre-flight OPTIONS da origine autorizzata deve restituire 200 e tutti gli header CORS configurati")
        void testCorsPreflight_AuthorizedOrigin_Returns200AndCorsHeaders() throws Exception {
                mockMvc.perform(options("/api/prenotazioni")
                                .header("Origin", AUTHORIZED_ORIGIN)
                                .header("Access-Control-Request-Method", "POST")
                                .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                                .andExpect(status().isOk())
                                .andExpect(header().string("Access-Control-Allow-Origin", AUTHORIZED_ORIGIN))
                                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")))
                                .andExpect(header().string("Access-Control-Allow-Methods", containsString("GET")))
                                .andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")))
                                .andExpect(header().string("Access-Control-Allow-Methods", containsString("DELETE")))
                                .andExpect(header().string("Access-Control-Allow-Headers",
                                                containsString("Authorization")))
                                .andExpect(header().string("Access-Control-Allow-Headers",
                                                containsString("Content-Type")))
                                .andExpect(header().string("Access-Control-Max-Age", "3600"))
                                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }

        @Test
        @DisplayName("Pre-flight OPTIONS deve esporre gli header Retry-After e Location al client frontend")
        void testCorsPreflight_ExposedHeaders_IncludesRetryAfterAndLocation() throws Exception {
                mockMvc.perform(options("/api/prenotazioni")
                                .header("Origin", AUTHORIZED_ORIGIN)
                                .header("Access-Control-Request-Method", "POST"))
                                .andExpect(status().isOk())
                                .andExpect(header().string("Access-Control-Expose-Headers",
                                                containsString("Retry-After")))
                                .andExpect(header().string("Access-Control-Expose-Headers",
                                                containsString("Location")));
        }

        @Test
        @DisplayName("Pre-flight OPTIONS da origine NON autorizzata deve essere respinta (403 o no header CORS)")
        void testCorsPreflight_UnauthorizedOrigin_Rejected() throws Exception {
                MvcResult result = mockMvc.perform(options("/api/prenotazioni")
                                .header("Origin", UNAUTHORIZED_ORIGIN)
                                .header("Access-Control-Request-Method", "POST")
                                .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                                .andReturn();

                int status = result.getResponse().getStatus();
                String allowOrigin = result.getResponse().getHeader("Access-Control-Allow-Origin");

                // Se l'origine non è consentita, Spring CorsProcessor imposta 403 Forbidden
                // oppure omette Access-Control-Allow-Origin
                assertTrue(status == 403 || allowOrigin == null,
                                "Origine non autorizzata non deve ricevere Access-Control-Allow-Origin");
                assertNotEquals(UNAUTHORIZED_ORIGIN, allowOrigin,
                                "Access-Control-Allow-Origin non deve mai riflettere un'origine non autorizzata");
        }

        // =========================================================================
        // 2. TEST CHIAMATE EFFETTIVE CON ORIGIN (CORS ACTUAL REQUESTS)
        // =========================================================================

        @Test
        @DisplayName("Richiesta autenticata da origine autorizzata include Access-Control-Allow-Origin")
        void testActualRequest_AuthorizedOrigin_IncludesCorsHeader() throws Exception {
                String token = mockValidAdminJwt();
                when(saleService.getSale()).thenReturn(List.of(
                                SalaDTO.builder().id("s1").nome("Sala Test").email("s1@szn.it").build()));

                mockMvc.perform(get("/api/sale")
                                .header("Authorization", "Bearer " + token)
                                .header("Origin", AUTHORIZED_ORIGIN))
                                .andExpect(status().isOk())
                                .andExpect(header().string("Access-Control-Allow-Origin", AUTHORIZED_ORIGIN));
        }

        @Test
        @DisplayName("Richiesta da origine non autorizzata non deve ottenere l'header Access-Control-Allow-Origin")
        void testActualRequest_UnauthorizedOrigin_DoesNotExposeCorsHeader() throws Exception {
                String token = mockValidAdminJwt();
                MvcResult result = mockMvc.perform(get("/api/sale")
                                .header("Authorization", "Bearer " + token)
                                .header("Origin", ROGUE_ORIGIN))
                                .andReturn();

                String allowOrigin = result.getResponse().getHeader("Access-Control-Allow-Origin");
                assertNotEquals(ROGUE_ORIGIN, allowOrigin,
                                "Access-Control-Allow-Origin non deve riflettere domini non autorizzati");
        }

        // =========================================================================
        // 3. TEST HTTP SECURITY HEADERS (OWASP / NIS 2 COMPLIANCE)
        // =========================================================================

        @Test
        @DisplayName("Risposte HTTP devono contenere tutti gli header di sicurezza difensivi configurati")
        void testSecurityHeaders_PresentInHttpResponses() throws Exception {
                String token = mockValidAdminJwt();
                when(saleService.getSale()).thenReturn(List.of());

                mockMvc.perform(get("/api/sale")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                                .andExpect(header().string("X-Frame-Options", "DENY"))
                                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                                .andExpect(header().string("Content-Security-Policy",
                                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"));
        }

        @Test
        @DisplayName("Header di sicurezza difensivi devono essere presenti anche nelle risposte di errore (401)")
        void testSecurityHeaders_PresentInUnauthorizedErrorResponses() throws Exception {
                mockMvc.perform(get("/api/sale"))
                                .andExpect(status().isUnauthorized())
                                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                                .andExpect(header().string("X-Frame-Options", "DENY"))
                                .andExpect(header().string("Content-Security-Policy",
                                                containsString("frame-ancestors 'none'")));
        }

        @Test
        @DisplayName("Verifica assenza di leak di versione nei banner HTTP (Server, X-Powered-By)")
        void testNoVersionLeak_ServerAndPoweredByHeaders() throws Exception {
                String token = mockValidAdminJwt();
                MvcResult result = mockMvc.perform(get("/api/sale")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andReturn();

                // 1. X-Powered-By non deve esistere
                assertNull(result.getResponse().getHeader("X-Powered-By"),
                                "X-Powered-By header non deve essere presente nelle risposte HTTP");

                // 2. Se l'header Server è presente, non deve esporre versioni software
                // specifiche
                String serverHeader = result.getResponse().getHeader("Server");
                if (serverHeader != null) {
                        assertFalse(serverHeader.matches(".*[0-9]+\\.[0-9]+.*"),
                                        "L'header Server non deve divulgare numeri di versione: " + serverHeader);
                }
        }
}
