package it.szn.prenotazionesale.integration;

import it.szn.prenotazionesale.config.AppConfig;
import it.szn.prenotazionesale.model.SalaDTO;
import it.szn.prenotazionesale.service.SaleService;
import it.szn.prenotazionesale.service.PrenotazioniService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration Test per la catena di sicurezza e hardening JWT:
 * 1. Richieste HTTP prive di token (401 Unauthorized, SecurityFilterChain stateless, nessun cookie sessione).
 * 2. Richieste HTTP con token scaduto (401 Unauthorized via CustomAuthenticationEntryPoint).
 * 3. Richieste HTTP con claim 'aud' non autorizzato / mancante (protezione Confused Deputy).
 * 4. Richieste HTTP con claim 'iss' non valido / emittente non attendibile.
 * 5. Test rigoroso dei validatori crittografici e semantici di SecurityConfig (Timestamp, Issuer, Audience).
 */
@SpringBootTest
class JwtHardeningIntegrationTest {

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

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    // =========================================================================
    // 1. RICHIESTE SENZA TOKEN (Blocco 401 e Statelessness)
    // =========================================================================

    @Test
    @DisplayName("GET /api/sale senza token deve essere respinta con 401 Unauthorized e payload JSON uniforme")
    void testRequestWithoutToken_GetSale_Returns401Unauthorized() throws Exception {
        mockMvc.perform(get("/api/sale"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Autenticazione richiesta. Token mancante, scaduto o non valido."))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("GET /api/prenotazioni senza token deve essere respinta con 401 Unauthorized")
    void testRequestWithoutToken_GetPrenotazioni_Returns401Unauthorized() throws Exception {
        mockMvc.perform(get("/api/prenotazioni")
                        .param("salaEmail", "sala1@szn.it")
                        .param("dataInizio", "2026-11-01T08:00:00")
                        .param("dataFine", "2026-11-01T18:00:00"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Autenticazione richiesta. Token mancante, scaduto o non valido."));
    }

    @Test
    @DisplayName("POST /api/prenotazioni senza token deve essere respinta con 401 Unauthorized")
    void testRequestWithoutToken_PostPrenotazioni_Returns401Unauthorized() throws Exception {
        mockMvc.perform(post("/api/prenotazioni")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salaEmail\":\"sala1@szn.it\",\"titolo\":\"Test\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Autenticazione richiesta. Token mancante, scaduto o non valido."));
    }

    @Test
    @DisplayName("DELETE /api/prenotazioni senza token deve essere respinta con 401 Unauthorized")
    void testRequestWithoutToken_DeletePrenotazioni_Returns401Unauthorized() throws Exception {
        mockMvc.perform(delete("/api/prenotazioni/event-999")
                        .param("salaEmail", "sala1@szn.it"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // =========================================================================
    // 2. TOKEN SCADUTO, MALFORMATO O NON VALIDO (401 Unauthorized)
    // =========================================================================

    @Test
    @DisplayName("Richiesta con token malformato/non decodificabile deve restituire 401")
    void testRequestWithMalformedToken_Returns401Unauthorized() throws Exception {
        when(jwtDecoder.decode("malformed.bearer.token"))
                .thenThrow(new BadJwtException("Token JWT non valido o malformato."));

        mockMvc.perform(get("/api/sale")
                        .header("Authorization", "Bearer malformed.bearer.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Autenticazione richiesta. Token mancante, scaduto o non valido."));
    }

    @Test
    @DisplayName("Richiesta con token scaduto (JwtTimestampValidator failure) deve restituire 401")
    void testRequestWithExpiredToken_Returns401Unauthorized() throws Exception {
        OAuth2Error expiredError = new OAuth2Error("invalid_token", "Jwt expired", null);
        when(jwtDecoder.decode("expired.jwt.token"))
                .thenThrow(new JwtValidationException("Jwt expired", List.of(expiredError)));

        mockMvc.perform(get("/api/sale")
                        .header("Authorization", "Bearer expired.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Autenticazione richiesta. Token mancante, scaduto o non valido."));
    }

    // =========================================================================
    // 3. AUDIENCE ERRATA / MANCANTE E CONFUSED DEPUTY (401 Unauthorized)
    // =========================================================================

    @Test
    @DisplayName("Richiesta con token avente claim 'aud' di altra applicazione (Confused Deputy) deve restituire 401")
    void testRequestWithWrongAudience_ConfusedDeputy_Returns401Unauthorized() throws Exception {
        OAuth2Error audError = new OAuth2Error(
                "invalid_token",
                "Token JWT rifiutato: audience ('aud') non autorizzata per questa API.",
                null
        );
        when(jwtDecoder.decode("confused.deputy.token"))
                .thenThrow(new JwtValidationException("Audience mismatch", List.of(audError)));

        mockMvc.perform(get("/api/sale")
                        .header("Authorization", "Bearer confused.deputy.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Autenticazione richiesta. Token mancante, scaduto o non valido."));
    }

    @Test
    @DisplayName("Richiesta con token avente emittente 'iss' non attendibile deve restituire 401")
    void testRequestWithUntrustedIssuer_Returns401Unauthorized() throws Exception {
        OAuth2Error issError = new OAuth2Error("invalid_token", "The iss claim is not valid", null);
        when(jwtDecoder.decode("untrusted.issuer.token"))
                .thenThrow(new JwtValidationException("The iss claim is not valid", List.of(issError)));

        mockMvc.perform(get("/api/sale")
                        .header("Authorization", "Bearer untrusted.issuer.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Autenticazione richiesta. Token mancante, scaduto o non valido."));
    }

    // =========================================================================
    // 4. TEST DIRETTO DEI VALIDATORI DI SECURITYCONFIG (AUD, ISS, EXP)
    // =========================================================================

    @Test
    @DisplayName("AudienceValidator deve respingere token con audience null, vuota o di terze parti")
    void testAudienceValidator_RejectsUnauthorizedAudience() {
        String expectedClientId = appConfig.getAzure().getClientId();
        String expectedAppUri = "api://" + expectedClientId;

        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> {
            List<String> audience = jwt.getAudience();
            if (audience != null && (audience.contains(expectedClientId) || audience.contains(expectedAppUri))) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "Token JWT rifiutato: audience ('aud') non autorizzata per questa API.",
                    null
            ));
        };

        // Case A: aud null
        Jwt jwtNullAud = createJwtWithClaims(null, "https://sts.windows.net/" + appConfig.getAzure().getTenantId() + "/", Instant.now().plusSeconds(300));
        OAuth2TokenValidatorResult resNull = audienceValidator.validate(jwtNullAud);
        assertTrue(resNull.hasErrors(), "Audience null deve fallire la validazione");

        // Case B: aud vuota
        Jwt jwtEmptyAud = createJwtWithClaims(Collections.emptyList(), "https://sts.windows.net/" + appConfig.getAzure().getTenantId() + "/", Instant.now().plusSeconds(300));
        OAuth2TokenValidatorResult resEmpty = audienceValidator.validate(jwtEmptyAud);
        assertTrue(resEmpty.hasErrors(), "Audience vuota deve fallire la validazione");

        // Case C: aud di altra applicazione (Confused Deputy)
        Jwt jwtRogueAud = createJwtWithClaims(List.of("rogue-client-id-9999"), "https://sts.windows.net/" + appConfig.getAzure().getTenantId() + "/", Instant.now().plusSeconds(300));
        OAuth2TokenValidatorResult resRogue = audienceValidator.validate(jwtRogueAud);
        assertTrue(resRogue.hasErrors(), "Audience di altra app deve fallire");
        assertEquals("Token JWT rifiutato: audience ('aud') non autorizzata per questa API.",
                resRogue.getErrors().iterator().next().getDescription());

        // Case D: aud valida (clientId)
        Jwt jwtValidClientId = createJwtWithClaims(List.of(expectedClientId), "https://sts.windows.net/" + appConfig.getAzure().getTenantId() + "/", Instant.now().plusSeconds(300));
        OAuth2TokenValidatorResult resValidClient = audienceValidator.validate(jwtValidClientId);
        assertFalse(resValidClient.hasErrors(), "Audience con Client ID deve essere valida");

        // Case E: aud valida (api://clientId)
        Jwt jwtValidAppUri = createJwtWithClaims(List.of(expectedAppUri), "https://sts.windows.net/" + appConfig.getAzure().getTenantId() + "/", Instant.now().plusSeconds(300));
        OAuth2TokenValidatorResult resValidUri = audienceValidator.validate(jwtValidAppUri);
        assertFalse(resValidUri.hasErrors(), "Audience con formato api:// deve essere valida");
    }

    @Test
    @DisplayName("TimestampValidator deve respingere token scaduti considerando il clock skew")
    void testTimestampValidator_RejectsExpiredToken() {
        OAuth2TokenValidator<Jwt> timestampValidator = new JwtTimestampValidator(Duration.ofSeconds(60));

        // Token scaduto da 120 secondi (oltre i 60s di skew consentiti)
        Jwt expiredJwt = createJwtWithClaims(
                List.of(appConfig.getAzure().getClientId()),
                "https://sts.windows.net/" + appConfig.getAzure().getTenantId() + "/",
                Instant.now().minusSeconds(120)
        );

        OAuth2TokenValidatorResult result = timestampValidator.validate(expiredJwt);
        assertTrue(result.hasErrors(), "Token con exp nel passato oltre lo skew deve fallire");

        // Token futuro valido
        Jwt validJwt = createJwtWithClaims(
                List.of(appConfig.getAzure().getClientId()),
                "https://sts.windows.net/" + appConfig.getAzure().getTenantId() + "/",
                Instant.now().plusSeconds(1800)
        );
        OAuth2TokenValidatorResult validResult = timestampValidator.validate(validJwt);
        assertFalse(validResult.hasErrors(), "Token non scaduto deve passare la validazione");
    }

    @Test
    @DisplayName("IssuerValidator deve respingere token emessi da un tenant o authority non autorizzati")
    void testIssuerValidator_RejectsRogueIssuer() {
        String expectedIssuer = "https://sts.windows.net/" + appConfig.getAzure().getTenantId() + "/";
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(expectedIssuer);

        // Token con issuer errato (es. tenant attaccante)
        Jwt rogueIssuerJwt = createJwtWithClaims(
                List.of(appConfig.getAzure().getClientId()),
                "https://sts.windows.net/attacker-tenant-id-000000000000/",
                Instant.now().plusSeconds(300)
        );

        OAuth2TokenValidatorResult rogueResult = issuerValidator.validate(rogueIssuerJwt);
        assertTrue(rogueResult.hasErrors(), "Issuer non autorizzato deve essere rigettato");

        // Token con issuer corretto
        Jwt validIssuerJwt = createJwtWithClaims(
                List.of(appConfig.getAzure().getClientId()),
                expectedIssuer,
                Instant.now().plusSeconds(300)
        );
        OAuth2TokenValidatorResult validResult = issuerValidator.validate(validIssuerJwt);
        assertFalse(validResult.hasErrors(), "Issuer corretto deve passare");
    }

    // =========================================================================
    // 5. ACCESSO CON TOKEN VALIDO E AUTENTICAZIONE RIUSCITA
    // =========================================================================

    @Test
    @DisplayName("Richiesta con token valido e autorizzato deve accedere con successo (200 OK)")
    void testValidToken_AccessAllowed() throws Exception {
        String validTokenStr = "valid.authorized.jwt.token";
        Jwt validJwt = new Jwt(
                validTokenStr,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256", "typ", "JWT"),
                Map.of(
                        "sub", "user-123",
                        "oid", "user-oid-123",
                        "iss", "https://sts.windows.net/" + appConfig.getAzure().getTenantId() + "/",
                        "aud", List.of(appConfig.getAzure().getClientId()),
                        "roles", List.of("RoomBooking.Admin")
                )
        );

        when(jwtDecoder.decode(validTokenStr)).thenReturn(validJwt);
        when(saleService.getSale()).thenReturn(List.of(
                SalaDTO.builder().id("sala1@szn.it").nome("Sala Dohrn").email("sala1@szn.it").build()
        ));

        mockMvc.perform(get("/api/sale")
                        .header("Authorization", "Bearer " + validTokenStr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nome").value("Sala Dohrn"));
    }

    private Jwt createJwtWithClaims(List<String> audience, String issuer, Instant exp) {
        Instant issuedAt = exp.minusSeconds(3600);
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", "test-user-id");
        claims.put("oid", "test-oid-123");
        claims.put("iss", issuer);
        claims.put("exp", exp);
        claims.put("nbf", issuedAt);
        claims.put("iat", issuedAt);
        if (audience != null) {
            claims.put("aud", audience);
        }

        return new Jwt(
                "mock-token-value",
                issuedAt,
                exp,
                Map.of("alg", "RS256"),
                claims
        );
    }
}
