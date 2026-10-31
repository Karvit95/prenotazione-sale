package it.szn.prenotazionesale.concurrency;

import com.microsoft.graph.models.Event;
import it.szn.prenotazionesale.graph.GraphCalendarGateway;
import it.szn.prenotazionesale.audit.SecurityAuditService;
import it.szn.prenotazionesale.config.AppConfig;
import it.szn.prenotazionesale.controller.PrenotazioniController;
import it.szn.prenotazionesale.exception.ErrorResponse;
import it.szn.prenotazionesale.exception.GlobalExceptionHandler;
import it.szn.prenotazionesale.validator.PrenotazioneValidator;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.model.SalaDTO;
import it.szn.prenotazionesale.service.PrenotazioniService;
import it.szn.prenotazionesale.service.SaleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Test di carico e concorrenza multi-thread per il meccanismo Anti-Double Booking.
 * Sincronizza 10 thread simultanei tramite CountDownLatch per tentare la prenotazione
 * dello stesso slot temporale e della medesima sala riunioni.
 *
 * Verifica:
 * 1. Serializzazione atomica tramite ReentrantLock per sala.
 * 2. Prevenzione totale di double booking (TOCTOU).
 * 3. Esattamente 1 prenotazione vincente e 9 rifiuti per conflitto.
 * 4. Rilascio tempestivo dei lock anche a seguito di eccezioni o interruzioni.
 */
public class AntiDoubleBookingConcurrencyTest {

    private static final String SALA_EMAIL = "dohrn@szn.it";
    private static final String SALA2_EMAIL = "polifunzionale@szn.it";
    private static final String START_TIME = "2026-11-15T10:00:00";
    private static final String END_TIME = "2026-11-15T11:00:00";

    private GraphCalendarGateway calendarGateway;
    private SaleService saleService;
    private AppConfig appConfig;
    private ThreadPoolTaskExecutor taskExecutor;
    private PrenotazioniService prenotazioniService;
    private SecurityAuditService auditService;
    private PrenotazioniController controller;
    private GlobalExceptionHandler exceptionHandler;

    private ExecutorService threadPool;
    private List<Event> backendEventsStorage;

    @BeforeEach
    void setUp() {
        backendEventsStorage = new CopyOnWriteArrayList<>();

        calendarGateway = Mockito.mock(GraphCalendarGateway.class);
        saleService = Mockito.mock(SaleService.class);

        appConfig = new AppConfig();
        appConfig.setAdminRole("RoomBooking.Admin");

        taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(4);
        taskExecutor.setMaxPoolSize(8);
        taskExecutor.initialize();

        when(saleService.getSale()).thenReturn(List.of(
                SalaDTO.builder().id("room-1").nome("Sala Pietro Dohrn").email(SALA_EMAIL).build(),
                SalaDTO.builder().id("room-2").nome("Sala Polifunzionale").email(SALA2_EMAIL).build()
        ));

        // Mock gateway per restituire gli eventi attualmente salvati nel backend simulato
        when(calendarGateway.getEventi(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> new ArrayList<>(backendEventsStorage));

        // Mock gateway creaEvento per salvare l'evento con simulazione di latenza I/O
        when(calendarGateway.creaEvento(anyString(), any(Event.class)))
                .thenAnswer(invocation -> {
                    Event eventToSave = invocation.getArgument(1);
                    Event savedEvent = new Event();
                    savedEvent.setId("event-" + UUID.randomUUID());
                    savedEvent.setSubject(eventToSave.getSubject());
                    savedEvent.setStart(eventToSave.getStart());
                    savedEvent.setEnd(eventToSave.getEnd());
                    savedEvent.setBody(eventToSave.getBody());

                    // Breve latenza di rete/elaborazione I/O per allargare la finestra di race condition
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }

                    backendEventsStorage.add(savedEvent);
                    return savedEvent;
                });

        prenotazioniService = new PrenotazioniService(calendarGateway, saleService, appConfig, taskExecutor, new PrenotazioneValidator());

        auditService = Mockito.mock(SecurityAuditService.class);
        controller = new PrenotazioniController(prenotazioniService, auditService, new MockHttpServletRequest());
        exceptionHandler = new GlobalExceptionHandler(auditService);

        threadPool = Executors.newFixedThreadPool(12);
    }

    @AfterEach
    void tearDown() {
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdownNow();
        }
        if (taskExecutor != null) {
            taskExecutor.shutdown();
        }
    }

    private Jwt buildAdminJwt(String userId) {
        return new Jwt(
                "token-" + userId,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("oid", userId, "roles", List.of("RoomBooking.Admin"))
        );
    }

    private PrenotazioneRequestDTO buildBookingRequest(String sala, String title) {
        return PrenotazioneRequestDTO.builder()
                .salaEmail(sala)
                .titolo(title)
                .start(START_TIME)
                .end(END_TIME)
                .build();
    }

    @Test
    @DisplayName("Anti-Double Booking: 10 thread simultanei sulla stessa sala e slot orario devono produrre esattamente 1 successo e 9 conflitti")
    void test10ConcurrentThreadsBookingSameRoomAndSlot_ExactOneSucceedsAndNineFailWithConflict()
            throws InterruptedException {
        int numberOfThreads = 10;
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCounter = new AtomicInteger(0);
        AtomicInteger conflictCounter = new AtomicInteger(0);
        AtomicInteger unexpectedErrorCounter = new AtomicInteger(0);
        List<Throwable> unexpectedExceptions = new CopyOnWriteArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadId = i;
            threadPool.submit(() -> {
                readyLatch.countDown();
                try {
                    // Attesa del rilascio simultaneo per innescare la massima contesa
                    startLatch.await();

                    Jwt jwt = buildAdminJwt("user-" + threadId);
                    PrenotazioneRequestDTO request = buildBookingRequest(SALA_EMAIL, "Riunione Concorrente #" + threadId);

                    PrenotazioneDTO result = prenotazioniService.creaPrenotazione(request, jwt);
                    if (result != null && result.getId() != null) {
                        successCounter.incrementAndGet();
                    }
                } catch (IllegalArgumentException ex) {
                    // Conflitto di disponibilità rilevato da verificaDisponibilita
                    if (ex.getMessage() != null && ex.getMessage().contains("gia occupata")) {
                        conflictCounter.incrementAndGet();
                    } else {
                        unexpectedErrorCounter.incrementAndGet();
                        unexpectedExceptions.add(ex);
                    }
                } catch (IllegalStateException ex) {
                    // Possibile timeout del lock (se coda thread supera timeout)
                    if (ex.getMessage() != null && ex.getMessage().contains("lock")) {
                        conflictCounter.incrementAndGet();
                    } else {
                        unexpectedErrorCounter.incrementAndGet();
                        unexpectedExceptions.add(ex);
                    }
                } catch (Throwable t) {
                    unexpectedErrorCounter.incrementAndGet();
                    unexpectedExceptions.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Tutti i thread sono pronti
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "Tutti i thread dovevano essere pronti entro 5 secondi");

        // Rilascio simultaneo di tutti i 10 thread
        startLatch.countDown();

        // Attesa completamento di tutti i task
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Tutti i 10 thread dovevano completare entro 15 secondi");

        // Asserzioni fondamentali Anti-Double Booking
        assertThat(unexpectedExceptions).as("Non devono verificarsi errori inattesi").isEmpty();
        assertEquals(0, unexpectedErrorCounter.get(), "Nessun errore imprevisto deve essere sollevato");
        assertEquals(1, successCounter.get(), "Esattamente 1 sola prenotazione deve vincere la contesa");
        assertEquals(9, conflictCounter.get(), "Esattamente 9 prenotazioni concorrenti devono essere respinte per conflitto");
        assertEquals(1, backendEventsStorage.size(), "Il backend deve contenere esattamente 1 evento registrato: NESSUNA DOUBLE BOOKING!");

        // Verifica che il ReentrantLock associato alla sala sia stato completamente rilasciato
        ReentrantLock lock = extractLockForSala(SALA_EMAIL);
        assertNotNull(lock, "Il lock per la sala deve esistere");
        assertFalse(lock.isLocked(), "Il lock per la sala deve essere rilasciato e NON bloccato");
        assertEquals(0, lock.getHoldCount(), "Il conteggio di hold del lock deve essere pari a 0");
    }

    @Test
    @DisplayName("Concorrenza multi-sala: prenotazioni simultanee su sale differenti devono procedere in parallelo senza blocchi reciproci")
    void testConcurrentBookingDifferentRooms_BothSucceedWithoutInterference() throws InterruptedException {
        int threadsPerRoom = 5;
        int totalThreads = threadsPerRoom * 2;

        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successRoom1 = new AtomicInteger(0);
        AtomicInteger conflictRoom1 = new AtomicInteger(0);
        AtomicInteger successRoom2 = new AtomicInteger(0);
        AtomicInteger conflictRoom2 = new AtomicInteger(0);

        for (int i = 0; i < totalThreads; i++) {
            final int index = i;
            final String sala = (index % 2 == 0) ? SALA_EMAIL : SALA2_EMAIL;
            final AtomicInteger successCounter = (index % 2 == 0) ? successRoom1 : successRoom2;
            final AtomicInteger conflictCounter = (index % 2 == 0) ? conflictRoom1 : conflictRoom2;

            threadPool.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    Jwt jwt = buildAdminJwt("user-" + index);
                    PrenotazioneRequestDTO request = buildBookingRequest(sala, "Meeting Multi-Room #" + index);

                    PrenotazioneDTO res = prenotazioniService.creaPrenotazione(request, jwt);
                    if (res != null) {
                        successCounter.incrementAndGet();
                    }
                } catch (IllegalArgumentException ex) {
                    if (ex.getMessage() != null && ex.getMessage().contains("gia occupata")) {
                        conflictCounter.incrementAndGet();
                    }
                } catch (Throwable ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        startLatch.countDown();
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS));

        // Entrambe le sale devono avere 1 prenotazione vincente e 4 conflitti
        assertEquals(1, successRoom1.get(), "Sala 1 deve consentire esattamente 1 prenotazione");
        assertEquals(4, conflictRoom1.get(), "Sala 1 deve respingere 4 tentativi in conflitto");
        assertEquals(1, successRoom2.get(), "Sala 2 deve consentire esattamente 1 prenotazione");
        assertEquals(4, conflictRoom2.get(), "Sala 2 deve respingere 4 tentativi in conflitto");

        // Verifica stato lock per entrambe le sale
        ReentrantLock lock1 = extractLockForSala(SALA_EMAIL);
        ReentrantLock lock2 = extractLockForSala(SALA2_EMAIL);
        assertNotNull(lock1);
        assertNotNull(lock2);
        assertFalse(lock1.isLocked(), "Lock Sala 1 deve essere rilasciato");
        assertFalse(lock2.isLocked(), "Lock Sala 2 deve essere rilasciato");
    }

    @Test
    @DisplayName("Resilienza: rilascio garantito del lock nel blocco finally in caso di eccezione durante la creazione")
    void testLockReleaseOnGraphException_PreventsDeadlockForSubsequentRequests() {
        // Simuliamo un errore Graph API durante la chiamata di post
        when(calendarGateway.creaEvento(anyString(), any(Event.class)))
                .thenThrow(new RuntimeException("Simulazione errore imprevisto Microsoft Graph API"));

        Jwt jwt = buildAdminJwt("user-err");
        PrenotazioneRequestDTO request = buildBookingRequest(SALA_EMAIL, "Prenotazione con errore Graph");

        // La chiamata deve fallire
        assertThrows(RuntimeException.class, () -> prenotazioniService.creaPrenotazione(request, jwt));

        // Verifichiamo che il lock sia stato comunque rilasciato nel blocco finally
        ReentrantLock lock = extractLockForSala(SALA_EMAIL);
        assertNotNull(lock);
        assertFalse(lock.isLocked(), "Il lock deve essere rilasciato anche a seguito di un'eccezione imprevista");
        assertEquals(0, lock.getHoldCount());

        // Ripristiniamo il funzionamento normale di Graph per verificare che la sala sia ancora prenotabile
        when(calendarGateway.creaEvento(anyString(), any(Event.class)))
                .thenAnswer(inv -> {
                    Event event = inv.getArgument(1);
                    event.setId("recovered-id-123");
                    backendEventsStorage.add(event);
                    return event;
                });

        // La richiesta successiva deve acquisire immediatamente il lock e avere successo (nessun deadlock orfano)
        PrenotazioneDTO recovered = prenotazioniService.creaPrenotazione(request, jwt);
        assertNotNull(recovered);
        assertEquals("recovered-id-123", recovered.getId());
        assertFalse(lock.isLocked(), "Il lock deve rimanere libero dopo il completamento");
    }

    @Test
    @DisplayName("Resilienza: gestione dell'interruzione di thread durante l'acquisizione del lock")
    void testLockAcquisitionInterruption_RestoresInterruptedStatusAndReleasesLock() throws Exception {
        ReentrantLock salaLock = extractOrCreateLockForSala(SALA_EMAIL);

        // Blocchiamo il lock sul thread principale per forzare il thread worker in attesa
        salaLock.lock();
        try {
            CountDownLatch threadStartedLatch = new CountDownLatch(1);
            AtomicInteger interruptedCaught = new AtomicInteger(0);

            Thread workerThread = new Thread(() -> {
                threadStartedLatch.countDown();
                Jwt jwt = buildAdminJwt("user-interrupted");
                PrenotazioneRequestDTO request = buildBookingRequest(SALA_EMAIL, "Prenotazione Interrotta");

                try {
                    prenotazioniService.creaPrenotazione(request, jwt);
                } catch (IllegalStateException ex) {
                    if (ex.getMessage() != null && ex.getMessage().contains("interrotta")) {
                        interruptedCaught.incrementAndGet();
                    }
                }
            });

            workerThread.start();
            assertTrue(threadStartedLatch.await(2, TimeUnit.SECONDS));
            // Breve sleep per garantire che il worker sia bloccato su tryLock
            Thread.sleep(100);

            // Interrompiamo il worker mentre è in attesa del lock
            workerThread.interrupt();
            workerThread.join(3000);

            assertEquals(1, interruptedCaught.get(),
                    "Il thread interrotto deve sollevare IllegalStateException con messaggio esplicito");
        } finally {
            salaLock.unlock();
        }

        assertFalse(salaLock.isLocked(), "Il lock deve essere libero");
    }

    @Test
    @DisplayName("Integrazione Controller + GlobalExceptionHandler: 10 chiamate concorrenti restituiscono 1 HTTP 200 OK e 9 risposte di errore controllate")
    void testControllerConcurrentExecution_WithGlobalExceptionHandler() throws InterruptedException {
        int numberOfThreads = 10;
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger http200Count = new AtomicInteger(0);
        AtomicInteger httpErrorCount = new AtomicInteger(0);
        List<Integer> statusCodes = new CopyOnWriteArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            final int id = i;
            threadPool.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    Jwt jwt = buildAdminJwt("controller-user-" + id);
                    PrenotazioneRequestDTO request = buildBookingRequest(SALA_EMAIL, "Controller Meeting #" + id);

                    try {
                        ResponseEntity<PrenotazioneDTO> response = controller.creaPrenotazione(request, jwt);
                        if (response.getStatusCode() == HttpStatus.OK) {
                            http200Count.incrementAndGet();
                            statusCodes.add(response.getStatusCode().value());
                        }
                    } catch (IllegalArgumentException ex) {
                        ResponseEntity<ErrorResponse> errorResp = exceptionHandler.handleIllegalArgument(ex);
                        httpErrorCount.incrementAndGet();
                        statusCodes.add(errorResp.getStatusCode().value());
                        assertTrue(errorResp.getBody().getMessage().contains("gia occupata"));
                    } catch (IllegalStateException ex) {
                        ResponseEntity<ErrorResponse> errorResp = exceptionHandler.handleIllegalState(ex, new MockHttpServletRequest());
                        httpErrorCount.incrementAndGet();
                        statusCodes.add(errorResp.getStatusCode().value());
                    } catch (Exception ex) {
                        ResponseEntity<ErrorResponse> errorResp = exceptionHandler.handleGeneric(ex);
                        httpErrorCount.incrementAndGet();
                        statusCodes.add(errorResp.getStatusCode().value());
                    }
                } catch (Throwable ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        startLatch.countDown();
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS));

        assertEquals(1, http200Count.get(), "Esattamente 1 chiamata deve restituire HTTP 200 OK");
        assertEquals(9, httpErrorCount.get(), "Esattamente 9 chiamate devono restituire codice di errore HTTP");
        assertThat(statusCodes).contains(200);
        // Nessun errore 500 generico non gestito deve verificarsi
        assertThat(statusCodes).doesNotContain(500);

        ReentrantLock lock = extractLockForSala(SALA_EMAIL);
        assertNotNull(lock);
        assertFalse(lock.isLocked());
    }

    // --- Metodi di supporto Reflection per ispezione stato interno ReentrantLock ---

    @SuppressWarnings("unchecked")
    private ReentrantLock extractLockForSala(String salaEmail) {
        try {
            Field lockMapField = PrenotazioniService.class.getDeclaredField("lockPerSala");
            lockMapField.setAccessible(true);
            ConcurrentHashMap<String, ReentrantLock> map =
                    (ConcurrentHashMap<String, ReentrantLock>) lockMapField.get(prenotazioniService);
            return map.get(salaEmail.trim().toLowerCase());
        } catch (Exception e) {
            throw new RuntimeException("Impossibile accedere al campo riflessivo lockPerSala", e);
        }
    }

    @SuppressWarnings("unchecked")
    private ReentrantLock extractOrCreateLockForSala(String salaEmail) {
        try {
            Field lockMapField = PrenotazioniService.class.getDeclaredField("lockPerSala");
            lockMapField.setAccessible(true);
            ConcurrentHashMap<String, ReentrantLock> map =
                    (ConcurrentHashMap<String, ReentrantLock>) lockMapField.get(prenotazioniService);
            return map.computeIfAbsent(salaEmail.trim().toLowerCase(), k -> new ReentrantLock());
        } catch (Exception e) {
            throw new RuntimeException("Impossibile accedere al campo riflessivo lockPerSala", e);
        }
    }
}
