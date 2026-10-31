package it.szn.prenotazionesale.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.model.SalaDTO;
import it.szn.prenotazionesale.service.PrenotazioniService;
import it.szn.prenotazionesale.service.SaleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.admin-role=Custom.Admin")
@DisplayName("Sicurezza Dinamica - Configurazione app.admin-role personalizzato (Custom.Admin)")
class DynamicAdminRoleSecurityIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private SaleService saleService;

    @MockitoBean
    private GraphServiceClient graphClient;

    @MockitoBean
    private PrenotazioniService prenotazioniService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        when(saleService.getSale()).thenReturn(List.of(
                SalaDTO.builder().id("sala1@szn.it").nome("Sala Dohrn").email("sala1@szn.it").build()
        ));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customAdminJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_Custom.Admin"))
                .jwt(j -> j.claim("roles", List.of("Custom.Admin"))
                        .claim("oid", "custom-admin-001")
                        .claim("preferred_username", "custom.admin@szn.it"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor oldDefaultAdminJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_RoomBooking.Admin"))
                .jwt(j -> j.claim("roles", List.of("RoomBooking.Admin"))
                        .claim("oid", "old-admin-002")
                        .claim("preferred_username", "old.admin@szn.it"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor userWithoutRolesJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                .jwt(j -> j.claim("oid", "user-003")
                        .claim("preferred_username", "utente@szn.it"));
    }

    private PrenotazioneRequestDTO buildValidBookingRequest() {
        return PrenotazioneRequestDTO.builder()
                .salaEmail("sala1@szn.it")
                .titolo("Meeting Progetto")
                .start("2026-11-20T10:00:00")
                .end("2026-11-20T11:00:00")
                .build();
    }

    @Test
    @DisplayName("POST /api/prenotazioni: consentito per Custom.Admin, vietato (403) per RoomBooking.Admin e utente senza ruoli")
    void testCreateBooking_AuthorizationLevels() throws Exception {
        PrenotazioneRequestDTO request = buildValidBookingRequest();
        when(prenotazioniService.creaPrenotazione(any(), any())).thenReturn(
                PrenotazioneDTO.builder().id("evt-new-1").salaEmail("sala1@szn.it").build()
        );

        // 1. Ruolo custom configurato: successo (200)
        mockMvc.perform(post("/api/prenotazioni")
                        .with(customAdminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // 2. Ruolo hardcoded precedente (RoomBooking.Admin): 403 Forbidden
        mockMvc.perform(post("/api/prenotazioni")
                        .with(oldDefaultAdminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // 3. Utente privo di ruoli admin: 403 Forbidden
        mockMvc.perform(post("/api/prenotazioni")
                        .with(userWithoutRolesJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /api/prenotazioni/{id}: consentito per Custom.Admin, vietato (403) per RoomBooking.Admin e utente senza ruoli")
    void testUpdateBooking_AuthorizationLevels() throws Exception {
        PrenotazioneRequestDTO request = buildValidBookingRequest();
        when(prenotazioniService.modificaPrenotazione(eq("evt-1"), any(), any())).thenReturn(
                PrenotazioneDTO.builder().id("evt-1").salaEmail("sala1@szn.it").build()
        );

        // 1. Ruolo custom configurato: successo (200)
        mockMvc.perform(patch("/api/prenotazioni/evt-1")
                        .with(customAdminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // 2. Vecchio default admin: 403 Forbidden
        mockMvc.perform(patch("/api/prenotazioni/evt-1")
                        .with(oldDefaultAdminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // 3. Utente privo di ruoli admin: 403 Forbidden
        mockMvc.perform(patch("/api/prenotazioni/evt-1")
                        .with(userWithoutRolesJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/prenotazioni/{id}: consentito per Custom.Admin (204), vietato (403) per RoomBooking.Admin e utente senza ruoli")
    void testDeleteBooking_AuthorizationLevels() throws Exception {
        // 1. Ruolo custom configurato: successo (204 No Content)
        mockMvc.perform(delete("/api/prenotazioni/evt-delete-1")
                        .with(customAdminJwt())
                        .param("salaEmail", "sala1@szn.it")
                        .param("tipoCancellazione", "SINGOLA"))
                .andExpect(status().isNoContent());

        // 2. Vecchio default admin: 403 Forbidden
        mockMvc.perform(delete("/api/prenotazioni/evt-delete-1")
                        .with(oldDefaultAdminJwt())
                        .param("salaEmail", "sala1@szn.it")
                        .param("tipoCancellazione", "SINGOLA"))
                .andExpect(status().isForbidden());

        // 3. Utente privo di ruoli admin: 403 Forbidden
        mockMvc.perform(delete("/api/prenotazioni/evt-delete-1")
                        .with(userWithoutRolesJwt())
                        .param("salaEmail", "sala1@szn.it")
                        .param("tipoCancellazione", "SINGOLA"))
                .andExpect(status().isForbidden());
    }
}

