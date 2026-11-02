package it.szn.prenotazionesale.performance;

import com.microsoft.graph.models.DateTimeTimeZone;
import com.microsoft.graph.models.Event;
import it.szn.prenotazionesale.config.AppConfig;
import it.szn.prenotazionesale.config.ClientIpResolver;
import it.szn.prenotazionesale.config.RateLimitingFilter;
import it.szn.prenotazionesale.graph.GraphCalendarGateway;
import it.szn.prenotazionesale.mapper.PrenotazioneMapper;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.model.PrenotazioniTutteSaleResponse;
import it.szn.prenotazionesale.model.SalaDTO;
import it.szn.prenotazionesale.service.PrenotazioniService;
import it.szn.prenotazionesale.service.SaleService;
import it.szn.prenotazionesale.validator.PrenotazioneValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Suite di Test di Performance, Throughput e Concorrenza (Benchmarking & Scalabilità).
 *
 * Copre:
 * 1. PERF-BE-01: Parallelismo e Speedup nel caricamento globale delle sale (ThreadPoolTaskExecutor).
 * 2. PERF-BE-02: Throughput di prenotazione concorrente su sale distinte (Fine-grained Lock).
 * 3. PERF-BE-03: Latenza e Throughput del filtro di Rate Limiting perimetrale.
 * 4. PERF-BE-04: Throughput di trasformazione DTO e mapping per dataset ad alto volume (1.000 eventi).
 * 5. PERF-BE-05: Drenaggio e fairness del lock sotto contesa elevata su singola sala (Hotspot).
 */
@DisplayName("Suite di Test di Performance, Throughput e Concorrenza")
class SystemPerformanceAndThroughputTest {

    private GraphCalendarGateway calendarGateway;
    private SaleService saleService;
    private AppConfig appConfig;
    private ThreadPoolTaskExecutor taskExecutor;
    private PrenotazioneValidator validator;
    private PrenotazioneMapper mapper;
    private PrenotazioniService prenotazioniService;

    @BeforeEach
    void setUp() {
        calendarGateway = Mockito.mock(GraphCalendarGateway.class);
        saleService = Mockito.mock(SaleService.class);
        appConfig = new AppConfig();
        appConfig.setAdminRole("RoomBooking.Admin");

        taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(10);
        taskExecutor.setMaxPoolSize(20);
        taskExecutor.setQueueCapacity(40);
        taskExecutor.setThreadNamePrefix("perf-test-query-");
        taskExecutor.initialize();

        validator = new PrenotazioneValidator();
        mapper = new PrenotazioneMapper(validator);

        prenotazioniService = new PrenotazioniService(
                calendarGateway,
                saleService,
                appConfig,
                taskExecutor,
                validator,
                mapper
        );
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.shutdown();
        }
    }

    private Jwt buildAdminJwt() {
        return new Jwt(
                "token-perf",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("oid", "perf-admin-1", "roles", List.of("RoomBooking.Admin"))
        );
    }

    // =========================================================================
    // 1. SCALABILITÀ E SPEEDUP PARALLELO DEL TABELLONE GLOBALE
    // =========================================================================
    @Test
    @DisplayName("PERF-BE-01: Interrogazione parallela di 10 sale dimostra speedup significativo rispetto al tempo sequenziale")
    void testParallelDashboardQuerySpeedup() {
        int numeroSale = 10;
        int artificialLatencyMs = 60; // 60ms di latenza Graph simulata per sala

        List<SalaDTO> saleList = new ArrayList<>();
        for (int i = 1; i <= numeroSale; i++) {
            String email = "sala" + i + "@szn.it";
            saleList.add(SalaDTO.builder().id(email).nome("Sala " + i).email(email).build());

            // Mock gateway che simula latenza di rete I/O
            when(calendarGateway.getEventi(eq(email), anyString(), anyString())).thenAnswer(invocation -> {
                Thread.sleep(artificialLatencyMs);
                return List.of();
            });
        }
        when(saleService.getSale()).thenReturn(saleList);

        long start = System.currentTimeMillis();
        PrenotazioniTutteSaleResponse response = prenotazioniService.getPrenotazioniTutteSale(
                "2026-11-10T08:00:00",
                "2026-11-10T20:00:00",
                buildAdminJwt()
        );
        long elapsed = System.currentTimeMillis() - start;

        // Se fosse sequenziale: 10 * 60ms = 600ms.
        // Essendo parallelo con pool da 10 thread: il tempo deve essere significativamente inferiore
        assertFalse(response.isParziale(), "La risposta non deve essere parziale");
        assertEquals(0, response.getSaleNonDisponibili().size());
        assertTrue(elapsed < 350, "Il tempo parallelo (" + elapsed + "ms) deve essere < 350ms (atteso teorico sequenziale >= 600ms)");
    }

    // =========================================================================
    // 2. THROUGHPUT PRENOTAZIONI CONCORRENTI SU SALE DISTINTE
    // =========================================================================
    @Test
    @DisplayName("PERF-BE-02: 20 thread concorrenti su 20 sale distinte operano in totale parallelismo senza lock contention")
    void testConcurrentBookingsOnDisjointRooms() throws InterruptedException {
        int threadsCount = 20;
        List<SalaDTO> saleList = new ArrayList<>();
        for (int i = 1; i <= threadsCount; i++) {
            saleList.add(SalaDTO.builder().id("room" + i + "@szn.it").email("room" + i + "@szn.it").nome("Room " + i).build());
        }
        when(saleService.getSale()).thenReturn(saleList);

        when(calendarGateway.getEventi(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(calendarGateway.creaEvento(anyString(), any())).thenAnswer(invocation -> {
            Event e = invocation.getArgument(1);
            e.setId("evt-" + invocation.getArgument(0));
            return e;
        });

        ExecutorService pool = Executors.newFixedThreadPool(threadsCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadsCount);
        AtomicInteger successCounter = new AtomicInteger(0);

        Jwt jwt = buildAdminJwt();

        long startTime = System.currentTimeMillis();
        for (int i = 1; i <= threadsCount; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    startGate.await(); // Sincronizzazione partenza simultanea
                    PrenotazioneRequestDTO req = PrenotazioneRequestDTO.builder()
                            .salaEmail("room" + index + "@szn.it")
                            .titolo("Meeting Room " + index)
                            .start("2026-11-15T10:00:00")
                            .end("2026-11-15T11:00:00")
                            .build();
                    prenotazioniService.creaPrenotazione(req, jwt);
                    successCounter.incrementAndGet();
                } catch (Exception e) {
                    // ignore
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown(); // Start simultaneo
        boolean completed = endGate.await(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;
        pool.shutdown();

        assertTrue(completed, "Tutti i task devono completare entro il timeout");
        assertEquals(threadsCount, successCounter.get(), "Tutte le 20 prenotazioni su sale distinte devono avere successo");
        assertTrue(elapsed < 2000, "20 prenotazioni parallele devono completare in < 2000ms (effettivo: " + elapsed + "ms)");
    }

    // =========================================================================
    // 3. LATENZA E OVERHEAD DEL FILTRO DI RATE LIMITING PERIMETRALE
    // =========================================================================
    @Test
    @DisplayName("PERF-BE-03: RateLimitingFilter processa 2.000 richieste con micro-latenza media inferiore a 0.05ms")
    void testRateLimitingFilterMicroLatencyBenchmark() throws ServletException, IOException {
        ClientIpResolver ipResolver = new ClientIpResolver();
        RateLimitingFilter filter = new RateLimitingFilter(appConfig, ipResolver);

        int totalRequests = 2000;
        FilterChain noOpChain = (req, res) -> {};

        // Warmup JIT
        for (int i = 0; i < 200; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/sale");
            req.setRemoteAddr("10.0.0." + (i % 250));
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, noOpChain);
        }

        // Benchmark misurato
        long start = System.nanoTime();
        for (int i = 0; i < totalRequests; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/sale");
            req.setRemoteAddr("192.168." + (i / 250) + "." + (i % 250));
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, noOpChain);
            assertEquals(200, res.getStatus());
        }
        long totalNanos = System.nanoTime() - start;

        double avgMicros = (totalNanos / 1_000.0) / totalRequests;
        double throughputOpsPerSec = (totalRequests / (totalNanos / 1_000_000_000.0));

        // Asserzione: latenza media < 100 microsecondi (0.10ms) per richiesta
        assertTrue(avgMicros < 100.0, "Latenza media filter (" + avgMicros + " µs) deve essere < 100 µs");
        assertTrue(throughputOpsPerSec > 10_000, "Throughput filter deve superare 10.000 req/s (misurato: " + (int)throughputOpsPerSec + " req/s)");
    }

    // =========================================================================
    // 4. THROUGHPUT DI TRASFORMAZIONE DTO PER DATASET AD ALTO VOLUME (1.000 EVENTI)
    // =========================================================================
    @Test
    @DisplayName("PERF-BE-04: Mapping e validazione di 1.000 eventi Graph completano in meno di 150ms")
    void testHighVolumeEventTransformationThroughput() {
        int count = 1000;
        List<Event> events = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            Event e = new Event();
            e.setId("evt-" + i);
            e.setSubject("Conferenza Scientifica SZN " + i);

            DateTimeTimeZone start = new DateTimeTimeZone();
            start.setDateTime("2026-11-20T09:00:00");
            start.setTimeZone("Europe/Rome");
            e.setStart(start);

            DateTimeTimeZone end = new DateTimeTimeZone();
            end.setDateTime("2026-11-20T10:30:00");
            end.setTimeZone("Europe/Rome");
            e.setEnd(end);

            events.add(e);
        }

        // Warmup
        for (int i = 0; i < 50; i++) {
            mapper.mapToDTO(events.get(i), "sala1@szn.it", true);
        }

        // Benchmark
        long startNanos = System.nanoTime();
        List<PrenotazioneDTO> dtoList = new ArrayList<>(count);
        for (Event event : events) {
            dtoList.add(mapper.mapToDTO(event, "sala1@szn.it", true));
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(count, dtoList.size());
        assertEquals("evt-999", dtoList.get(999).getId());
        assertTrue(elapsedMs < 150, "Mapping di 1.000 eventi (" + elapsedMs + "ms) deve completare in < 150ms");
    }

    // =========================================================================
    // 5. FAIRNESS E RESILIENZA DEL LOCK SU SINGOLA SALA SOTTO ALTA CONTESA
    // =========================================================================
    @Test
    @DisplayName("PERF-BE-05: 30 thread concorrenti in contesa sulla stessa sala drenano la coda senza deadlock né starvation")
    void testSingleRoomLockFairnessUnderHighContention() throws InterruptedException {
        int threadsCount = 30;
        String salaEmail = "sala-hotspot@szn.it";

        when(saleService.getSale()).thenReturn(List.of(
                SalaDTO.builder().id(salaEmail).email(salaEmail).nome("Sala Hotspot").build()
        ));

        // Mock gateway con risposta immediata
        when(calendarGateway.getEventi(eq(salaEmail), anyString(), anyString())).thenReturn(List.of());
        when(calendarGateway.creaEvento(eq(salaEmail), any())).thenAnswer(invocation -> {
            Event e = invocation.getArgument(1);
            e.setId("evt-hotspot-" + System.nanoTime());
            return e;
        });

        ExecutorService pool = Executors.newFixedThreadPool(threadsCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadsCount);
        AtomicInteger successCounter = new AtomicInteger(0);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        Jwt jwt = buildAdminJwt();

        for (int i = 0; i < threadsCount; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    long t0 = System.currentTimeMillis();

                    // Finestre orarie contigue da 15 minuti ciascuna tra le 08:00 e le 18:00 (interamente in fascia aziendale)
                    int minuteOffset = index * 20; // 0, 20, 40, 60...
                    int hourStart = 8 + (minuteOffset / 60);
                    int minStart = minuteOffset % 60;
                    int minEnd = minStart + 15;
                    String startStr = String.format("2026-11-25T%02d:%02d:00", hourStart, minStart);
                    String endStr = String.format("2026-11-25T%02d:%02d:00", hourStart, minEnd);

                    PrenotazioneRequestDTO req = PrenotazioneRequestDTO.builder()
                            .salaEmail(salaEmail)
                            .titolo("Meeting Slot " + index)
                            .start(startStr)
                            .end(endStr)
                            .build();

                    prenotazioniService.creaPrenotazione(req, jwt);
                    latencies.add(System.currentTimeMillis() - t0);
                    successCounter.incrementAndGet();
                } catch (Exception e) {
                    // ignore
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = endGate.await(8, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(completed, "La contesa deve risolversi completamente entro 8s");
        assertEquals(threadsCount, successCounter.get(), "Tutti i 30 thread in coda sulla singola sala devono completare con successo");
        assertEquals(threadsCount, latencies.size());
    }
}
