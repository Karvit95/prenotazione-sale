package it.szn.prenotazionesale.controller;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.audit.SecurityAuditService;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioniTutteSaleResponse;
import it.szn.prenotazionesale.model.SalaNonDisponibileDTO;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@DisplayName("PrenotazioniController - GET /api/prenotazioni/tutte Integrazione Risultato Parziale")
class PrenotazioniTutteSaleControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean
    private PrenotazioniService prenotazioniService;

    @MockitoBean
    private SecurityAuditService auditService;

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
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor testUserJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                .jwt(j -> j.claim("oid", "user-123")
                        .claim("preferred_username", "ricercatore@szn.it"));
    }

    @Test
    @DisplayName("GET /api/prenotazioni/tutte parziale: body privo di information disclosure e audit GRAPH_PARTIAL_RESULT registrato")
    void testGetPrenotazioniTutteSale_PartialResult_LogsAuditAndHidesExceptionDetails() throws Exception {
        PrenotazioniTutteSaleResponse mockResponse = PrenotazioniTutteSaleResponse.builder()
                .prenotazioni(List.of(
                        PrenotazioneDTO.builder()
                                .id("evt-101")
                                .salaEmail("sala.dohrn@szn.it")
                                .titolo("Conferenza Clima")
                                .start("2026-11-20T09:00:00")
                                .end("2026-11-20T11:00:00")
                                .build()
                ))
                .saleNonDisponibili(List.of(
                        SalaNonDisponibileDTO.builder()
                                .salaEmail("sala.fermi@szn.it")
                                .salaNome("Sala Fermi")
                                .motivo("ERRORE_GRAPH")
                                .build()
                ))
                .parziale(true)
                .totaleSale(2)
                .build();

        when(prenotazioniService.getPrenotazioniTutteSale(eq("2026-11-20T08:00:00"), eq("2026-11-20T20:00:00"), any()))
                .thenReturn(mockResponse);

        mockMvc.perform(get("/api/prenotazioni/tutte")
                        .with(testUserJwt())
                        .param("dataInizio", "2026-11-20T08:00:00")
                        .param("dataFine", "2026-11-20T20:00:00")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parziale").value(true))
                .andExpect(jsonPath("$.prenotazioni").isArray())
                .andExpect(jsonPath("$.prenotazioni[0].id").value("evt-101"))
                .andExpect(jsonPath("$.saleNonDisponibili").isArray())
                .andExpect(jsonPath("$.saleNonDisponibili[0].salaEmail").value("sala.fermi@szn.it"))
                .andExpect(jsonPath("$.saleNonDisponibili[0].salaNome").value("Sala Fermi"))
                .andExpect(jsonPath("$.saleNonDisponibili[0].motivo").value("ERRORE_GRAPH"))
                // Assicura l'assenza totale di leak di informazioni di debug / messaggi di errore
                .andExpect(jsonPath("$.saleNonDisponibili[0].message").doesNotExist())
                .andExpect(jsonPath("$.saleNonDisponibili[0].dettagli").doesNotExist())
                .andExpect(jsonPath("$.saleNonDisponibili[0].exception").doesNotExist())
                .andExpect(jsonPath("$.totaleSale").doesNotExist());

        // Verifica emissione audit event GRAPH_PARTIAL_RESULT
        verify(auditService).logGraphIncident(
                eq("GRAPH_PARTIAL_RESULT"),
                eq("/api/prenotazioni/tutte"),
                eq(200),
                argThat(msg -> msg != null && msg.contains("1 sale su 2 non disponibili: sala.fermi@szn.it")),
                anyString()
        );
    }
}

