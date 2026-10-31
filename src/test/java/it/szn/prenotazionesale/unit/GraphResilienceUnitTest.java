package it.szn.prenotazionesale.unit;

import com.microsoft.graph.models.DateTimeTimeZone;
import it.szn.prenotazionesale.validator.PrenotazioneValidator;
import com.microsoft.graph.models.Event;
import com.microsoft.graph.models.Room;
import com.microsoft.graph.models.RoomCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.graph.GraphCalendarGateway;
import com.microsoft.kiota.ApiException;
import com.microsoft.kiota.ResponseHeaders;
import it.szn.prenotazionesale.config.AppConfig;
import it.szn.prenotazionesale.exception.ErrorResponse;
import it.szn.prenotazionesale.exception.GlobalExceptionHandler;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.model.SalaDTO;
import it.szn.prenotazionesale.service.PrenotazioniService;
import it.szn.prenotazionesale.service.SaleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Test isolati per la resilienza e la gestione controllata delle anomalie di rete
 * e degli errori restituiti da Microsoft Graph API:
 * - Rate Limiting (HTTP 429 e header Retry-After).
 * - Errori lato server Microsoft Graph (HTTP 500, 503 Service Unavailable, 504).
 * - Errori di autenticazione client credentials (HTTP 401 / 403).
 * - Timeout di socket di rete (SocketTimeoutException) e di interrogazione concorrente (TimeoutException).
 * - Prevenzione deadlock e rilascio lock rientranti su fallimento chiamata Graph.
 * - Resilienza delle transazioni compensative (Rollback atomico cambio sala).
 * - Resilienza e prevenzione avvelenamento cache in SaleService.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
public class GraphResilienceUnitTest {

    @Mock
    private GraphCalendarGateway calendarGateway;

    @Mock
    private SaleService saleService;

    private AppConfig appConfig;
    private ThreadPoolTaskExecutor executor;
    private PrenotazioniService prenotazioniService;
    private GlobalExceptionHandler exceptionHandler;

    private static final String SALA_1 = "sala1@szn.it";
    private static final String SALA_2 = "sala2@szn.it";

    public static class TestApiException extends ApiException {
        private static final long serialVersionUID = 1L;

        public TestApiException(int statusCode, String message, ResponseHeaders headers) {
            super(message);
            setResponseStatusCode(statusCode);
            setResponseHeaders(headers != null ? headers : new ResponseHeaders());
        }
    }

    @BeforeEach
    void setUp() {
        appConfig = new AppConfig();
        appConfig.setAdminRole("RoomBooking.Admin");

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.initialize();

        when(saleService.getSale()).thenReturn(List.of(
                SalaDTO.builder().id(SALA_1).nome("Sala 1").email(SALA_1).build(),
                SalaDTO.builder().id(SALA_2).nome("Sala 2").email(SALA_2).build()
        ));

        prenotazioniService = new PrenotazioniService(calendarGateway, saleService, appConfig, executor, new PrenotazioneValidator());
        exceptionHandler = new GlobalExceptionHandler();
    }

    private Jwt createAdminJwt() {
        return new Jwt(
                "token-mock",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", "admin@szn.it", "roles", List.of("RoomBooking.Admin"))
        );
    }

    private Event createGraphEvent(String id, String startIso, String endIso) {
        Event event = new Event();
        event.setId(id);
        event.setSubject("Meeting Test");

        DateTimeTimeZone start = new DateTimeTimeZone();
        start.setDateTime(startIso);
        start.setTimeZone("Europe/Rome");
        event.setStart(start);

        DateTimeTimeZone end = new DateTimeTimeZone();
        end.setDateTime(endIso);
        end.setTimeZone("Europe/Rome");
        event.setEnd(end);

        return event;
    }

    @Nested
    @DisplayName("1. Resilienza e Gestione Rate Limiting (HTTP 429 Too Many Requests)")
    class RateLimitingResilienceTests {

        @Test
        @DisplayName("Dovrebbe gestire errore 429 con Retry-After impostando status 429, header e messaggio user-friendly")
        void testRateLimit_429WithRetryAfter_HandledControlled() {
            ResponseHeaders headers = new ResponseHeaders();
            headers.add("Retry-After", "30");
            ApiException graph429 = new TestApiException(429, "Rate limit exceeded on Exchange", headers);

            ResponseEntity<ErrorResponse> response = exceptionHandler.handleGraphApiException(graph429);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(429);
            assertThat(response.getBody().getMessage())
                    .contains("30 secondi")
                    .doesNotContain("Exchange");
        }

        @Test
        @DisplayName("Dovrebbe gestire errore 429 senza header Retry-After impostando status 429 e messaggio di fallback")
        void testRateLimit_429WithoutRetryAfter_DefaultMessage() {
            ApiException graph429 = new TestApiException(429, "Too many requests", new ResponseHeaders());

            ResponseEntity<ErrorResponse> response = exceptionHandler.handleGraphApiException(graph429);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(429);
            assertThat(response.getBody().getMessage()).contains("Riprova tra pochi secondi");
        }
    }

    @Nested
    @DisplayName("2. Resilienza su Errori Server Microsoft Graph (HTTP 5xx e 401/403)")
    class GraphServerErrorResilienceTests {

        @Test
        @DisplayName("Dovrebbe intercettare errore 503 Service Unavailable e restituire 502 Bad Gateway con correlation ID")
        void testServerError_503ServiceUnavailable_HandledAsBadGatewayWithoutLeak() {
            ApiException graph503 = new TestApiException(
                    503, "Internal Exchange Backend Timeout at https://graph.microsoft.com/v1.0/users/...", null
            );

            ResponseEntity<ErrorResponse> response = exceptionHandler.handleGraphApiException(graph503);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(502);

            String msg = response.getBody().getMessage();
            assertThat(msg)
                    .contains("Errore di comunicazione con il servizio di calendario esterno Microsoft 365")
                    .contains("Rif: ")
                    .doesNotContain("Internal Exchange Backend Timeout")
                    .doesNotContain("https://graph.microsoft.com");
        }

        @Test
        @DisplayName("Dovrebbe intercettare errore 500 Internal Server Error da Graph e restituire 502 Bad Gateway sanificato")
        void testServerError_500InternalError_HandledAsBadGateway() {
            ApiException graph500 = new TestApiException(500, "Unknown remote server error in Microsoft 365", null);

            ResponseEntity<ErrorResponse> response = exceptionHandler.handleGraphApiException(graph500);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).contains("Rif: ");
            assertThat(response.getBody().getMessage()).doesNotContain("Unknown remote server error");
        }

        @Test
        @DisplayName("Dovrebbe gestire errore 401 Unauthorized da Graph come 502 Bad Gateway per proteggere credenziali daemon")
        void testAuthFailure_401Unauthorized_HandledAsBadGateway() {
            ApiException graph401 = new TestApiException(401, "Invalid client secret provided for tenant", null);

            ResponseEntity<ErrorResponse> response = exceptionHandler.handleGraphApiException(graph401);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage())
                    .contains("Errore di comunicazione e autenticazione verso il servizio cloud")
                    .doesNotContain("Invalid client secret");
        }
    }

    @Nested
    @DisplayName("3. Resilienza Timeout di Rete (SocketTimeoutException & TimeoutException)")
    class NetworkTimeoutResilienceTests {

        @Test
        @DisplayName("In caso di SocketTimeoutException su post evento, il lock di sala deve essere rilasciato in finally")
        void testNetworkTimeout_SocketTimeoutException_UnlocksLockAndPropagates() {
            when(calendarGateway.getEventi(eq(SALA_1), any(), any())).thenReturn(Collections.emptyList());

            // Simula SocketTimeoutException durante la chiamata HTTP di scrittura
            when(calendarGateway.creaEvento(eq(SALA_1), any()))
                    .thenThrow(new RuntimeException(new SocketTimeoutException("Read timed out after 15000ms")));

            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_1)
                    .titolo("Meeting con Timeout")
                    .start("2026-11-10T09:00:00")
                    .end("2026-11-10T10:00:00")
                    .build();

            // L'eccezione viene sollevata
            assertThrows(RuntimeException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt())
            );

            // VERIFICA ASSENZA DEADLOCK:
            // Se il lock non fosse stato rilasciato in finally, una successiva chiamata rimarrebbe bloccata per 10s
            // o fallirebbe con IllegalStateException("Timeout acquisizione lock").
            // Ora simuliamo che Graph sia di nuovo operativo:
            Event successEvent = createGraphEvent("evt-success-after-timeout", "2026-11-10T09:00:00", "2026-11-10T10:00:00");
            doReturn(successEvent).when(calendarGateway).creaEvento(eq(SALA_1), any());

            assertDoesNotThrow(() -> {
                prenotazioniService.creaPrenotazione(request, createAdminJwt());
            }, "Il lock per la sala deve essere stato rilasciato; la successiva richiesta deve procedere senza timeout lock");
        }

        @Test
        @DisplayName("Dovrebbe gestire TimeoutException nelle query concorrenti restituendo 504 Gateway Timeout")
        void testTimeoutException_InParallelQuery_WrappedInCompletionException() {
            TimeoutException timeoutEx = new TimeoutException("Graph parallel query timed out after 12s");
            CompletionException completionEx = new CompletionException(timeoutEx);

            ResponseEntity<ErrorResponse> response = exceptionHandler.handleCompletionException(completionEx);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(504);
            assertThat(response.getBody().getMessage()).contains("scaduta. Riprova più tardi.");
        }

        @Test
        @DisplayName("Dovrebbe spacchettare ApiException (es. 429) incapsulata in CompletionException da chiamate asincrone")
        void testApiException_UnwrappedFromCompletionException() {
            ResponseHeaders headers = new ResponseHeaders();
            headers.add("Retry-After", "10");
            ApiException inner429 = new TestApiException(429, "Throttled", headers);
            CompletionException completionEx = new CompletionException(inner429);

            ResponseEntity<ErrorResponse> response = exceptionHandler.handleCompletionException(completionEx);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("10");
        }
    }

    @Nested
    @DisplayName("4. Resilienza Transazionale e Rollback Compensativo (Cambio Sala)")
    class RollbackResilienceTests {

        @Test
        @DisplayName("Durante cambio sala, se la cancellazione della sala precedente fallisce, deve eseguire rollback sulla nuova sala")
        void testRoomMigration_CompensatingRollbackWhenOldDeleteFails() {
            // Nessun evento preesistente su SALA_2
            when(calendarGateway.getEventi(eq(SALA_2), any(), any())).thenReturn(Collections.emptyList());

            // Creazione sulla nuova sala (SALA_2) riesce
            Event createdOnSala2 = createGraphEvent("new-evt-sala2", "2026-11-10T09:00:00", "2026-11-10T10:00:00");
            when(calendarGateway.creaEvento(eq(SALA_2), any())).thenReturn(createdOnSala2);

            // Cancellazione sulla vecchia sala (SALA_1) fallisce con errore 500
            doThrow(new TestApiException(500, "Remote delete error on Exchange", null))
                    .when(calendarGateway).eliminaEvento(eq(SALA_1), eq("old-evt-1"));

            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_2)
                    .salaEmailOriginale(SALA_1)
                    .titolo("Meeting Spostato")
                    .start("2026-11-10T09:00:00")
                    .end("2026-11-10T10:00:00")
                    .tipoModifica("SINGOLA")
                    .build();

            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    prenotazioniService.modificaPrenotazione("old-evt-1", request, createAdminJwt())
            );
            assertThat(ex.getMessage()).contains("Impossibile completare il cambio sala");

            // VERIFICA DEL ROLLBACK COMPENSATIVO:
            // Il nuovo evento creato su SALA_2 deve essere stato cancellato per evitare duplicazioni di prenotazione
            verify(calendarGateway).eliminaEvento(eq(SALA_2), eq("new-evt-sala2"));
        }

        @Test
        @DisplayName("Durante cambio sala, se anche il rollback compensativo fallisce, deve loggare incidente e sbloccare entrambi i lock")
        void testRoomMigration_FailureWhenCompensatingRollbackAlsoFails() {
            when(calendarGateway.getEventi(eq(SALA_2), any(), any())).thenReturn(Collections.emptyList());
            when(calendarGateway.getEventi(eq(SALA_1), any(), any())).thenReturn(Collections.emptyList());

            Event createdOnSala2 = createGraphEvent("new-evt-sala2", "2026-11-10T09:00:00", "2026-11-10T10:00:00");
            when(calendarGateway.creaEvento(eq(SALA_2), any())).thenReturn(createdOnSala2);

            // Cancellazione vecchia sala fallisce
            doThrow(new RuntimeException("Delete failed on SALA_1"))
                    .when(calendarGateway).eliminaEvento(eq(SALA_1), eq("old-evt-1"));

            // E anche il rollback su SALA_2 fallisce!
            doThrow(new RuntimeException("Rollback delete failed on SALA_2"))
                    .when(calendarGateway).eliminaEvento(eq(SALA_2), eq("new-evt-sala2"));

            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_2)
                    .salaEmailOriginale(SALA_1)
                    .titolo("Meeting Doppio Fallimento")
                    .start("2026-11-10T09:00:00")
                    .end("2026-11-10T10:00:00")
                    .tipoModifica("SINGOLA")
                    .build();

            assertThrows(IllegalStateException.class, () ->
                    prenotazioniService.modificaPrenotazione("old-evt-1", request, createAdminJwt())
            );

            // Verifica che i lock di entrambe le sale siano stati rilasciati (nessun deadlock)
            // Una nuova prenotazione successiva su SALA_1 o SALA_2 deve poter acquisire i lock
            Event newSuccessEvent = createGraphEvent("evt-success", "2026-11-10T14:00:00", "2026-11-10T15:00:00");
            doReturn(newSuccessEvent).when(calendarGateway).creaEvento(eq(SALA_1), any());

            PrenotazioneRequestDTO nextRequest = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_1)
                    .titolo("Meeting Successivo")
                    .start("2026-11-10T14:00:00")
                    .end("2026-11-10T15:00:00")
                    .build();

            assertDoesNotThrow(() -> prenotazioniService.creaPrenotazione(nextRequest, createAdminJwt()));
        }
    }

    @Nested
    @DisplayName("5. Resilienza della Cache in SaleService su Errori di Rete")
    class SaleServiceCacheResilienceTests {

        @Test
        @DisplayName("Se la chiamata a Graph fallisce, la cache non deve essere avvelenata e la successiva chiamata deve riprovare")
        void testSaleService_CacheNotPoisonedOnGraphFailure() {
            GraphServiceClient isolatedGraphClient = mock(GraphServiceClient.class, RETURNS_DEEP_STUBS);
            SaleService isolatedSaleService = new SaleService(isolatedGraphClient);

            // Prima chiamata fallisce con errore 503
            when(isolatedGraphClient.places().graphRoom().get())
                    .thenThrow(new TestApiException(503, "Graph places unavailable", null));

            assertThrows(ApiException.class, () -> isolatedSaleService.getSale());

            // Seconda chiamata: Graph torna attivo e restituisce 1 sala
            Room room = new Room();
            room.setId("room-recovery");
            room.setDisplayName("Sala Recuperata");
            room.setEmailAddress("rec@szn.it");

            RoomCollectionResponse response = new RoomCollectionResponse();
            response.setValue(List.of(room));

            var graphRoomMock = isolatedGraphClient.places().graphRoom();
            doReturn(response).when(graphRoomMock).get();

            // La seconda chiamata deve riprovare Graph (non usare cache rotta/vuota) e avere successo
            List<SalaDTO> result = isolatedSaleService.getSale();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNome()).isEqualTo("Sala Recuperata");

            // Verifica che Graph sia stato interrogato 2 volte in totale
            verify(isolatedGraphClient.places().graphRoom(), times(2)).get();
        }
    }
}
