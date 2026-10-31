package it.szn.prenotazionesale.service;

import com.microsoft.graph.models.DateTimeTimeZone;
import com.microsoft.graph.models.Event;
import com.microsoft.kiota.ApiException;
import it.szn.prenotazionesale.config.AppConfig;
import it.szn.prenotazionesale.graph.GraphCalendarGateway;
import it.szn.prenotazionesale.mapper.PrenotazioneMapper;
import it.szn.prenotazionesale.model.PrenotazioniTutteSaleResponse;
import it.szn.prenotazionesale.model.SalaDTO;
import it.szn.prenotazionesale.validator.PrenotazioneValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PrenotazioniService - getPrenotazioniTutteSale con risultati parziali")
class PrenotazioniServiceTutteSaleTest {

    @Mock
    private GraphCalendarGateway calendarGateway;

    @Mock
    private SaleService saleService;

    private ThreadPoolTaskExecutor executor;
    private PrenotazioniService service;
    private Jwt adminJwt;

    private static class TestApiException extends ApiException {
        private static final long serialVersionUID = 1L;

        public TestApiException(int statusCode, String message) {
            super(message);
            setResponseStatusCode(statusCode);
        }
    }

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.initialize();

        AppConfig appConfig = new AppConfig();
        PrenotazioneValidator validator = new PrenotazioneValidator();
        PrenotazioneMapper mapper = new PrenotazioneMapper(validator);

        service = new PrenotazioniService(
                calendarGateway,
                saleService,
                appConfig,
                executor,
                validator,
                mapper
        );

        adminJwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                Map.of("roles", List.of("RoomBooking.Admin"), "oid", "admin-1", "preferred_username", "admin@szn.it")
        );
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    private Event createSampleEvent(String id, String subject) {
        Event event = new Event();
        event.setId(id);
        event.setSubject(subject);

        DateTimeTimeZone start = new DateTimeTimeZone();
        start.setDateTime("2026-11-20T09:00:00");
        start.setTimeZone("Europe/Rome");
        event.setStart(start);

        DateTimeTimeZone end = new DateTimeTimeZone();
        end.setDateTime("2026-11-20T10:00:00");
        end.setTimeZone("Europe/Rome");
        event.setEnd(end);

        return event;
    }

    @Test
    @DisplayName("Tre sale: una fallisce con ApiException -> parziale=true, prenotazioni delle altre due presenti, sala fallita con motivo ERRORE_GRAPH")
    void testGetPrenotazioniTutteSale_OneRoomFailsWithApiException_ReturnsPartialResult() {
        SalaDTO sala1 = SalaDTO.builder().id("s1").email("sala1@szn.it").nome("Sala Dohrn").build();
        SalaDTO sala2 = SalaDTO.builder().id("s2").email("sala2@szn.it").nome("Sala Fermi").build();
        SalaDTO sala3 = SalaDTO.builder().id("s3").email("sala3@szn.it").nome("Sala Actinia").build();

        when(saleService.getSale()).thenReturn(List.of(sala1, sala2, sala3));

        when(calendarGateway.getEventi(eq("sala1@szn.it"), any(), any()))
                .thenReturn(List.of(createSampleEvent("evt-1", "Meeting 1")));
        when(calendarGateway.getEventi(eq("sala2@szn.it"), any(), any()))
                .thenReturn(List.of(createSampleEvent("evt-2", "Meeting 2")));
        when(calendarGateway.getEventi(eq("sala3@szn.it"), any(), any()))
                .thenThrow(new TestApiException(500, "Internal Microsoft Server Error"));

        PrenotazioniTutteSaleResponse response = service.getPrenotazioniTutteSale(
                "2026-11-20T08:00:00", "2026-11-20T20:00:00", adminJwt);

        assertNotNull(response);
        assertTrue(response.isParziale(), "La risposta deve essere contrassegnata come parziale");
        assertEquals(2, response.getPrenotazioni().size(), "Le prenotazioni delle 2 sale riuscite devono essere presenti");
        assertEquals(1, response.getSaleNonDisponibili().size(), "Deve esserci esattamente 1 sala non disponibile");

        var nonDisp = response.getSaleNonDisponibili().get(0);
        assertEquals("sala3@szn.it", nonDisp.getSalaEmail());
        assertEquals("Sala Actinia", nonDisp.getSalaNome());
        assertEquals("ERRORE_GRAPH", nonDisp.getMotivo());
    }

    @Test
    @DisplayName("Una sala fallisce per Timeout -> parziale=true, motivo TIMEOUT per la sala non disponibile")
    void testGetPrenotazioniTutteSale_OneRoomTimesOut_ReturnsTimeoutMotivo() {
        SalaDTO sala1 = SalaDTO.builder().id("s1").email("sala1@szn.it").nome("Sala Dohrn").build();
        SalaDTO sala2 = SalaDTO.builder().id("s2").email("sala2@szn.it").nome("Sala Fermi").build();

        when(saleService.getSale()).thenReturn(List.of(sala1, sala2));

        when(calendarGateway.getEventi(eq("sala1@szn.it"), any(), any()))
                .thenReturn(List.of(createSampleEvent("evt-1", "Meeting 1")));
        when(calendarGateway.getEventi(eq("sala2@szn.it"), any(), any()))
                .thenAnswer(invocation -> {
                    throw new RuntimeException(new TimeoutException("Simulated timeout"));
                });

        PrenotazioniTutteSaleResponse response = service.getPrenotazioniTutteSale(
                "2026-11-20T08:00:00", "2026-11-20T20:00:00", adminJwt);

        assertTrue(response.isParziale());
        assertEquals(1, response.getPrenotazioni().size());
        assertEquals(1, response.getSaleNonDisponibili().size());
        assertEquals("sala2@szn.it", response.getSaleNonDisponibili().get(0).getSalaEmail());
        assertEquals("TIMEOUT", response.getSaleNonDisponibili().get(0).getMotivo());
    }

    @Test
    @DisplayName("Tutte le sale falliscono -> l'eccezione originale viene rilanciata tramite CompletionException")
    void testGetPrenotazioniTutteSale_AllRoomsFail_PropagatesOriginalException() {
        SalaDTO sala1 = SalaDTO.builder().id("s1").email("sala1@szn.it").nome("Sala Dohrn").build();
        SalaDTO sala2 = SalaDTO.builder().id("s2").email("sala2@szn.it").nome("Sala Fermi").build();

        when(saleService.getSale()).thenReturn(List.of(sala1, sala2));

        when(calendarGateway.getEventi(eq("sala1@szn.it"), any(), any()))
                .thenThrow(new TestApiException(429, "Throttling"));
        when(calendarGateway.getEventi(eq("sala2@szn.it"), any(), any()))
                .thenThrow(new TestApiException(500, "Error"));

        CompletionException thrown = assertThrows(CompletionException.class, () ->
                service.getPrenotazioniTutteSale("2026-11-20T08:00:00", "2026-11-20T20:00:00", adminJwt));

        assertTrue(thrown.getCause() instanceof ApiException,
                "La causa deve essere l'eccezione originale ApiException");
        assertEquals(429, ((ApiException) thrown.getCause()).getResponseStatusCode());
    }

    @Test
    @DisplayName("Nessun fallimento -> parziale=false, saleNonDisponibili vuota, tutte le prenotazioni presenti nell'ordine delle sale")
    void testGetPrenotazioniTutteSale_AllRoomsSucceed_ReturnsCompleteResult() {
        SalaDTO sala1 = SalaDTO.builder().id("s1").email("sala1@szn.it").nome("Sala Dohrn").build();
        SalaDTO sala2 = SalaDTO.builder().id("s2").email("sala2@szn.it").nome("Sala Fermi").build();

        when(saleService.getSale()).thenReturn(List.of(sala1, sala2));

        when(calendarGateway.getEventi(eq("sala1@szn.it"), any(), any()))
                .thenReturn(List.of(createSampleEvent("evt-1", "Meeting 1")));
        when(calendarGateway.getEventi(eq("sala2@szn.it"), any(), any()))
                .thenReturn(List.of(createSampleEvent("evt-2", "Meeting 2")));

        PrenotazioniTutteSaleResponse response = service.getPrenotazioniTutteSale(
                "2026-11-20T08:00:00", "2026-11-20T20:00:00", adminJwt);

        assertFalse(response.isParziale());
        assertTrue(response.getSaleNonDisponibili().isEmpty());
        assertEquals(2, response.getPrenotazioni().size());
        assertEquals("evt-1", response.getPrenotazioni().get(0).getId());
        assertEquals("evt-2", response.getPrenotazioni().get(1).getId());
    }

    @Test
    @DisplayName("Data non valida -> IllegalArgumentException e nessuna chiamata concorrente al gateway")
    void testGetPrenotazioniTutteSale_InvalidDateRange_ThrowsIllegalArgumentExceptionBeforeTasks() {
        // dataInizio successiva a dataFine
        assertThrows(IllegalArgumentException.class, () ->
                service.getPrenotazioniTutteSale("2026-11-20T12:00:00", "2026-11-20T10:00:00", adminJwt));

        verify(calendarGateway, never()).getEventi(any(), any(), any());
        verify(saleService, never()).getSale();
    }

    @Test
    @DisplayName("Nessuna sala configurata -> risposta vuota con parziale=false")
    void testGetPrenotazioniTutteSale_EmptyRooms_ReturnsEmptyResponse() {
        when(saleService.getSale()).thenReturn(Collections.emptyList());

        PrenotazioniTutteSaleResponse response = service.getPrenotazioniTutteSale(
                "2026-11-20T08:00:00", "2026-11-20T20:00:00", adminJwt);

        assertNotNull(response);
        assertFalse(response.isParziale());
        assertTrue(response.getPrenotazioni().isEmpty());
        assertTrue(response.getSaleNonDisponibili().isEmpty());
    }
}

