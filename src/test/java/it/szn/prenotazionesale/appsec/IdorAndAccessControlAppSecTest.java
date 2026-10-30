package it.szn.prenotazionesale.appsec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.serviceclient.GraphServiceClient;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suite di Test di Sicurezza Applicativa (OWASP AppSec):
 * Broken Access Control (OWASP Top 10 A01:2021) & Broken Object Level Authorization / BOLA (OWASP API Security Top 10 API1:2023).
 *
 * Verifica l'imposizione rigorosa dell'autorizzazione basata su ruoli (RBAC) e la protezione
 * da tentativi IDOR di manipolazione, cancellazione o cambio sala di prenotazioni da parte di
 * utenti non autorizzati o privi del ruolo 'RoomBooking.Admin'.
 */
@SpringBootTest
class IdorAndAccessControlAppSecTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private SaleService saleService;

    @MockitoBean
    private GraphServiceClient graphClient;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        when(saleService.getSale()).thenReturn(List.of(
                SalaDTO.builder().id("sala1@szn.it").nome("Sala Dohrn").email("sala1@szn.it").build(),
                SalaDTO.builder().id("sala2@szn.it").nome("Sala Fermi").email("sala2@szn.it").build()
        ));
    }

    // --- Helper per la generazione dei token JWT mockati ---

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_RoomBooking.Admin"))
                .jwt(j -> j.claim("roles", List.of("RoomBooking.Admin"))
                        .claim("oid", "admin-oid-001")
                        .claim("preferred_username", "admin@szn.it"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor standardUserJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                .jwt(j -> j.claim("roles", List.of("RoomBooking.User"))
                        .claim("oid", "user-oid-999")
                        .claim("preferred_username", "mario.rossi@szn.it"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor tokenWithoutRolesClaimJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                .jwt(j -> j.claim("oid", "user-oid-888")
                        .claim("preferred_username", "ospite@szn.it"));
    }

    private PrenotazioneRequestDTO buildValidBookingRequest(String salaEmail) {
        return PrenotazioneRequestDTO.builder()
                .salaEmail(salaEmail)
                .titolo("Meeting di Ricerca")
                .descrizione("Discussione progetto")
                .start("2026-11-20T10:00:00")
                .end("2026-11-20T11:00:00")
                .build();
    }

    @Nested
    @DisplayName("1. Tentativi di Modifica Prenotazioni Non Autorizzati (IDOR / Broken Access Control)")
    class ModificaPrenotazioniAccessControlTests {

        @Test
        @DisplayName("IDOR-01: Utente standard con ruolo 'RoomBooking.User' che tenta PATCH su prenotazione altrui viene bloccato con 403 Forbidden")
        void testPatchBooking_StandardUser_ReturnsForbidden403() throws Exception {
            PrenotazioneRequestDTO request = buildValidBookingRequest("sala1@szn.it");

            mockMvc.perform(patch("/api/prenotazioni/event-altrui-12345")
                            .with(standardUserJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.message").value("Non si dispone dei privilegi necessari per eseguire l'operazione richiesta."));
        }

        @Test
        @DisplayName("IDOR-02: Utente con token valido ma privo di qualsiasi ruolo che tenta PATCH su prenotazione viene bloccato con 403 Forbidden")
        void testPatchBooking_TokenWithoutRoles_ReturnsForbidden403() throws Exception {
            PrenotazioneRequestDTO request = buildValidBookingRequest("sala1@szn.it");

            mockMvc.perform(patch("/api/prenotazioni/event-master-999")
                            .with(tokenWithoutRolesClaimJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("IDOR-03: Richiesta anonima di PATCH senza Bearer token viene respinta con 401 Unauthorized")
        void testPatchBooking_AnonymousUser_ReturnsUnauthorized401() throws Exception {
            PrenotazioneRequestDTO request = buildValidBookingRequest("sala1@szn.it");

            mockMvc.perform(patch("/api/prenotazioni/event-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("IDOR-04: Tentativo di modifica tramite metodo HTTP PUT da parte di utente non-admin - verifica applicazione 403 Forbidden")
        void testPutBooking_StandardUser_VerifiesAccessControlEnforcement() throws Exception {
            // Un attaccante potrebbe tentare di eludere le regole di autorizzazione PATCH
            // inviando una richiesta PUT verso l'endpoint di prenotazione.
            // La sicurezza perimetrale deve respingere l'azione non autorizzata con 403 Forbidden.
            PrenotazioneRequestDTO request = buildValidBookingRequest("sala1@szn.it");

            mockMvc.perform(put("/api/prenotazioni/event-123")
                            .with(standardUserJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("2. Tentativi di Cancellazione Prenotazioni Non Autorizzati (IDOR / Broken Access Control)")
    class CancellazionePrenotazioniAccessControlTests {

        @Test
        @DisplayName("IDOR-05: Utente standard tenta DELETE di una prenotazione singola creata da altri - bloccato con 403 Forbidden")
        void testDeleteBooking_StandardUser_ReturnsForbidden403() throws Exception {
            mockMvc.perform(delete("/api/prenotazioni/event-altrui-555")
                            .with(standardUserJwt())
                            .param("salaEmail", "sala1@szn.it")
                            .param("tipoCancellazione", "SINGOLA"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.message").value("Non si dispone dei privilegi necessari per eseguire l'operazione richiesta."));
        }

        @Test
        @DisplayName("IDOR-06: Utente standard tenta DELETE di un'intera serie ricorrente (seriesMasterId) - bloccato con 403 Forbidden")
        void testDeleteSeries_StandardUser_ReturnsForbidden403() throws Exception {
            mockMvc.perform(delete("/api/prenotazioni/occurrence-123")
                            .with(standardUserJwt())
                            .param("salaEmail", "sala1@szn.it")
                            .param("tipoCancellazione", "SERIE")
                            .param("seriesMasterId", "master-series-777"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("IDOR-07: Richiesta anonima di DELETE senza Bearer token viene respinta con 401 Unauthorized")
        void testDeleteBooking_AnonymousUser_ReturnsUnauthorized401() throws Exception {
            mockMvc.perform(delete("/api/prenotazioni/event-123")
                            .param("salaEmail", "sala1@szn.it"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("3. Tentativi di Creazione Prenotazioni Non Autorizzati (Privilege Escalation)")
    class CreazionePrenotazioniAccessControlTests {

        @Test
        @DisplayName("IDOR-08: Utente standard tenta POST /api/prenotazioni - bloccato con 403 Forbidden")
        void testCreateBooking_StandardUser_ReturnsForbidden403() throws Exception {
            PrenotazioneRequestDTO request = buildValidBookingRequest("sala1@szn.it");

            mockMvc.perform(post("/api/prenotazioni")
                            .with(standardUserJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("IDOR-09: Utente anonimo tenta POST /api/prenotazioni - bloccato con 401 Unauthorized")
        void testCreateBooking_AnonymousUser_ReturnsUnauthorized401() throws Exception {
            PrenotazioneRequestDTO request = buildValidBookingRequest("sala1@szn.it");

            mockMvc.perform(post("/api/prenotazioni")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("4. BOLA / IDOR su Sale Non Autorizzate o Caselle Personali")
    class BolaMailboxAccessControlTests {

        @Test
        @DisplayName("BOLA-01: Tentativo di cambio sala verso casella personale dirigenziale (direttore.generale@szn.it) - verifica autorizzazione")
        void testChangeRoomToUnauthorizedPersonalMailbox_VerifiesAccessControl() throws Exception {
            // Simulazione di cambio sala verso una casella email personale o riservata non censita nelle sale
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("direttore.generale@szn.it")
                    .salaEmailOriginale("sala1@szn.it")
                    .titolo("Meeting Riservato")
                    .start("2026-11-20T10:00:00")
                    .end("2026-11-20T11:00:00")
                    .build();

            // Il sistema deve impedire la manipolazione verso caselle non autorizzate
            // restituendo 403 Forbidden secondo il profilo BOLA (OWASP API1:2023)
            mockMvc.perform(patch("/api/prenotazioni/event-100")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA-02: Tentativo di consultazione GET prenotazioni su casella personale dirigenziale - verifica autorizzazione BOLA")
        void testGetBookingsFromUnauthorizedPersonalMailbox_VerifiesAccessControl() throws Exception {
            // Attacco BOLA: un utente tenta di leggere gli appuntamenti della casella privata del Direttore Generale
            mockMvc.perform(get("/api/prenotazioni")
                            .with(standardUserJwt())
                            .param("salaEmail", "direttore.generale@szn.it")
                            .param("dataInizio", "2026-11-20T08:00:00")
                            .param("dataFine", "2026-11-20T18:00:00"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA-03: Manipolazione fraudolenta di seriesMasterId su altra sala durante PATCH - bloccato")
        void testTamperingSeriesMasterIdOnDifferentRoom_StandardUserBlocked() throws Exception {
            // Un utente non-admin tenta di inviare un PATCH dichiarando un seriesMasterId appartenente a un'altra sala
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala1@szn.it")
                    .salaEmailOriginale("sala2@szn.it")
                    .seriesMasterId("master-id-appartenente-a-sala2")
                    .tipoModifica("SERIE")
                    .titolo("Dirottamento Serie")
                    .start("2026-11-20T14:00:00")
                    .end("2026-11-20T15:00:00")
                    .build();

            mockMvc.perform(patch("/api/prenotazioni/occurrence-xyz")
                            .with(standardUserJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }
}
