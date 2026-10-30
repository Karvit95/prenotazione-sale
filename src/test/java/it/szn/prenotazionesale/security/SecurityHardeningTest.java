package it.szn.prenotazionesale.security;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.config.AppConfig;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.model.SalaDTO;
import it.szn.prenotazionesale.service.PrenotazioniService;
import it.szn.prenotazionesale.service.SaleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class SecurityHardeningTest {

    private PrenotazioniService prenotazioniService;
    private SaleService saleService;
    private AppConfig appConfig;

    @BeforeEach
    void setUp() {
        GraphServiceClient graphClient = Mockito.mock(GraphServiceClient.class);
        saleService = Mockito.mock(SaleService.class);
        appConfig = new AppConfig();
        appConfig.setAdminRole("RoomBooking.Admin");

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.initialize();

        when(saleService.getSale()).thenReturn(List.of(
                SalaDTO.builder().id("sala1@szn.it").nome("Sala Dohrn").email("sala1@szn.it").build()
        ));

        prenotazioniService = new PrenotazioniService(graphClient, saleService, appConfig, executor);
    }

    private Jwt buildMockAdminJwt() {
        return new Jwt(
                "mock-token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("oid", "user-oid-123", "roles", List.of("RoomBooking.Admin"))
        );
    }

    @Test
    void testSec01_BolaProtection_RejectsUnauthorizedMailbox() {
        Jwt jwt = buildMockAdminJwt();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                prenotazioniService.getPrenotazioni(
                        "direttore.generale@szn.it",
                        "2026-11-01T08:00:00",
                        "2026-11-02T18:00:00",
                        jwt
                )
        );
        assertTrue(ex.getMessage().contains("non e autorizzata"));
    }

    @Test
    void testSec05_DateRangeDoS_RejectsExcessiveRange() {
        Jwt jwt = buildMockAdminJwt();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                prenotazioniService.getPrenotazioni(
                        "sala1@szn.it",
                        "2026-01-01T00:00:00",
                        "2030-01-01T00:00:00",
                        jwt
                )
        );
        assertTrue(ex.getMessage().contains("non puo superare"));
    }

    @Test
    void testSec06_RecurrenceDoS_RejectsSeriesOverOneYear() {
        Jwt jwt = buildMockAdminJwt();
        PrenotazioneRequestDTO req = PrenotazioneRequestDTO.builder()
                .salaEmail("sala1@szn.it")
                .titolo("Riunione")
                .start("2026-11-01T09:00:00")
                .end("2026-11-01T10:00:00")
                .pattern("daily")
                .intervallo(1)
                .dataFine("2035-11-01")
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                prenotazioniService.creaPrenotazione(req, jwt)
        );
        assertTrue(ex.getMessage().contains("365 giorni"));
    }

    @Test
    void testSec14_ClientSecretExcludedFromToString() {
        AppConfig.Azure azure = new AppConfig.Azure();
        azure.setTenantId("tenant-123");
        azure.setClientId("client-123");
        azure.setClientSecret("SUPER_SECRET_VALUE_DO_NOT_LEAK");

        String dump = azure.toString();
        assertFalse(dump.contains("SUPER_SECRET_VALUE_DO_NOT_LEAK"));
    }
}

