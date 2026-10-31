package it.szn.prenotazionesale.unit;

import it.szn.prenotazionesale.mapper.PrenotazioneMapper;
import it.szn.prenotazionesale.validator.PrenotazioneValidator;
import com.microsoft.graph.models.DateTimeTimeZone;
import com.microsoft.graph.models.Event;
import it.szn.prenotazionesale.graph.GraphCalendarGateway;
import it.szn.prenotazionesale.config.AppConfig;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.model.SalaDTO;
import it.szn.prenotazionesale.service.PrenotazioniService;
import it.szn.prenotazionesale.service.SaleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Test isolati per la logica di business relativa alla gestione degli orari,
 * delle sovrapposizioni tra prenotazioni e della compatibilità con i fusi orari
 * (Europe/Rome e UTC).
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
public class BusinessHoursAndOverlapUnitTest {

    @Mock
    private GraphCalendarGateway calendarGateway;

    @Mock
    private SaleService saleService;

    private AppConfig appConfig;
    private ThreadPoolTaskExecutor executor;
    private PrenotazioniService prenotazioniService;

    private static final String SALA_TEST = "sala-dohrn@szn.it";
    private static final String SALA_ALTRA = "sala-polifunzionale@szn.it";

    @BeforeEach
    void setUp() {
        appConfig = new AppConfig();
        appConfig.setAdminRole("RoomBooking.Admin");

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.initialize();

        when(saleService.getSale()).thenReturn(List.of(
                SalaDTO.builder().id(SALA_TEST).nome("Sala Dohrn").email(SALA_TEST).build(),
                SalaDTO.builder().id(SALA_ALTRA).nome("Sala Polifunzionale").email(SALA_ALTRA).build()
        ));

        PrenotazioneValidator validator = new PrenotazioneValidator();
        PrenotazioneMapper mapper = new PrenotazioneMapper(validator);
        prenotazioniService = new PrenotazioniService(calendarGateway, saleService, appConfig, executor, validator, mapper);
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
        event.setSubject("Riunione Esistente");

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
    @DisplayName("1. Logica di Rilevamento Sovrapposizioni (Overlap Detection)")
    class OverlapDetectionTests {

        @Test
        @DisplayName("Dovrebbe rifiutare la prenotazione in caso di collisione temporale identica (slot identico)")
        void testOverlap_IdenticalSlot_ThrowsConflictException() {
            // Esistente: 2026-11-10 10:00 -> 11:00
            Event existingEvent = createGraphEvent("evt-1", "2026-11-10T10:00:00", "2026-11-10T11:00:00");
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(List.of(existingEvent));

            // Richiesta: 2026-11-10 10:00 -> 11:00
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Nuovo Meeting")
                    .start("2026-11-10T10:00:00")
                    .end("2026-11-10T11:00:00")
                    .build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt())
            );

            assertThat(ex.getMessage()).contains("gia occupata").contains("2026-11-10");
        }

        @Test
        @DisplayName("Dovrebbe rifiutare la prenotazione in caso di sovrapposizione parziale all'inizio (inizia prima e finisce dentro)")
        void testOverlap_NewStartsBeforeAndEndsDuringExisting_ThrowsConflictException() {
            // Esistente: 2026-11-10 10:00 -> 12:00
            Event existingEvent = createGraphEvent("evt-1", "2026-11-10T10:00:00", "2026-11-10T12:00:00");
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(List.of(existingEvent));

            // Richiesta: 09:30 -> 10:30
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Meeting Anticipato")
                    .start("2026-11-10T09:30:00")
                    .end("2026-11-10T10:30:00")
                    .build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt())
            );

            assertThat(ex.getMessage()).contains("gia occupata");
        }

        @Test
        @DisplayName("Dovrebbe rifiutare la prenotazione in caso di sovrapposizione parziale alla fine (inizia dentro e finisce dopo)")
        void testOverlap_NewStartsDuringAndEndsAfterExisting_ThrowsConflictException() {
            // Esistente: 2026-11-10 10:00 -> 12:00
            Event existingEvent = createGraphEvent("evt-1", "2026-11-10T10:00:00", "2026-11-10T12:00:00");
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(List.of(existingEvent));

            // Richiesta: 11:30 -> 12:30
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Meeting Prolungato")
                    .start("2026-11-10T11:30:00")
                    .end("2026-11-10T12:30:00")
                    .build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt())
            );

            assertThat(ex.getMessage()).contains("gia occupata");
        }

        @Test
        @DisplayName("Dovrebbe rifiutare se la nuova prenotazione racchiude completamente l'evento esistente")
        void testOverlap_NewCompletelyEnclosesExisting_ThrowsConflictException() {
            // Esistente: 2026-11-10 10:30 -> 11:00
            Event existingEvent = createGraphEvent("evt-1", "2026-11-10T10:30:00", "2026-11-10T11:00:00");
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(List.of(existingEvent));

            // Richiesta: 10:00 -> 12:00 (comprende l'intero evento esistente)
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Meeting Lungo")
                    .start("2026-11-10T10:00:00")
                    .end("2026-11-10T12:00:00")
                    .build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt())
            );

            assertThat(ex.getMessage()).contains("gia occupata");
        }

        @Test
        @DisplayName("Dovrebbe rifiutare se la nuova prenotazione è totalmente interna all'evento esistente")
        void testOverlap_ExistingCompletelyEnclosesNew_ThrowsConflictException() {
            // Esistente: 2026-11-10 09:00 -> 13:00
            Event existingEvent = createGraphEvent("evt-1", "2026-11-10T09:00:00", "2026-11-10T13:00:00");
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(List.of(existingEvent));

            // Richiesta: 10:00 -> 11:00 (interna a 09:00-13:00)
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Meeting Interno")
                    .start("2026-11-10T10:00:00")
                    .end("2026-11-10T11:00:00")
                    .build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt())
            );

            assertThat(ex.getMessage()).contains("gia occupata");
        }

        @Test
        @DisplayName("Dovrebbe consentire slot adiacenti consecutivi (nuovo evento termina esattamente quando inizia il successivo)")
        void testNonOverlap_AdjacentSlotBefore_Allowed() {
            // Esistente: 2026-11-10 10:00 -> 11:00
            Event existingEvent = createGraphEvent("evt-1", "2026-11-10T10:00:00", "2026-11-10T11:00:00");
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(List.of(existingEvent));

            Event createdMock = createGraphEvent("evt-new", "2026-11-10T09:00:00", "2026-11-10T10:00:00");
            when(calendarGateway.creaEvento(eq(SALA_TEST), any())).thenReturn(createdMock);

            // Richiesta: 09:00 -> 10:00 (fine = inizio evento esistente)
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Meeting Adiacente Prima")
                    .start("2026-11-10T09:00:00")
                    .end("2026-11-10T10:00:00")
                    .build();

            PrenotazioneDTO result = prenotazioniService.creaPrenotazione(request, createAdminJwt());
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo("evt-new");
        }

        @Test
        @DisplayName("Dovrebbe consentire slot adiacenti consecutivi (nuovo evento inizia esattamente quando termina il precedente)")
        void testNonOverlap_AdjacentSlotAfter_Allowed() {
            // Esistente: 2026-11-10 10:00 -> 11:00
            Event existingEvent = createGraphEvent("evt-1", "2026-11-10T10:00:00", "2026-11-10T11:00:00");
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(List.of(existingEvent));

            Event createdMock = createGraphEvent("evt-new-after", "2026-11-10T11:00:00", "2026-11-10T12:00:00");
            when(calendarGateway.creaEvento(eq(SALA_TEST), any())).thenReturn(createdMock);

            // Richiesta: 11:00 -> 12:00 (inizio = fine evento esistente)
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Meeting Adiacente Dopo")
                    .start("2026-11-10T11:00:00")
                    .end("2026-11-10T12:00:00")
                    .build();

            PrenotazioneDTO result = prenotazioniService.creaPrenotazione(request, createAdminJwt());
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo("evt-new-after");
        }

        @Test
        @DisplayName("Dovrebbe consentire prenotazioni contemporanee se effettuate su sale differenti")
        void testNonOverlap_SameTimeDifferentRoom_Allowed() {
            // Sala 2 non ha eventi
            when(calendarGateway.getEventi(eq(SALA_ALTRA), any(), any())).thenReturn(Collections.emptyList());

            Event createdMock = createGraphEvent("evt-sala2", "2026-11-10T10:00:00", "2026-11-10T11:00:00");
            when(calendarGateway.creaEvento(eq(SALA_ALTRA), any())).thenReturn(createdMock);

            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_ALTRA)
                    .titolo("Meeting in Altra Sala")
                    .start("2026-11-10T10:00:00")
                    .end("2026-11-10T11:00:00")
                    .build();

            PrenotazioneDTO result = prenotazioniService.creaPrenotazione(request, createAdminJwt());
            assertThat(result).isNotNull();
            assertThat(result.getSalaId()).isEqualTo(SALA_ALTRA);
        }

        @Test
        @DisplayName("Dovrebbe rilevare conflitto quando una serie ricorrente collide con un evento singolo esistente")
        void testOverlap_RecurringDailySeriesCollidesWithExistingSingleEvent() {
            // Esistente singolo mercoledì 2026-11-04 10:00 -> 11:00
            Event existingEvent = createGraphEvent("evt-wed", "2026-11-04T10:00:00", "2026-11-04T11:00:00");
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(List.of(existingEvent));

            // Richiesta: serie daily da lunedì 2026-11-02 a venerdì 2026-11-06 (orario 10:00-11:00)
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Serie Daily")
                    .start("2026-11-02T10:00:00")
                    .end("2026-11-02T11:00:00")
                    .pattern("daily")
                    .intervallo(1)
                    .dataFine("2026-11-06")
                    .build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt())
            );

            assertThat(ex.getMessage()).contains("gia occupata").contains("2026-11-04");
        }

        @Test
        @DisplayName("Dovrebbe escludere se stesso durante la modifica per evitare falsi positivi di sovrapposizione")
        void testOverlap_SelfExclusionWhenModifyingSameSlot() {
            // L'evento 'evt-123' esiste già nello stesso slot
            Event existingEvent = createGraphEvent("evt-123", "2026-11-10T10:00:00", "2026-11-10T11:00:00");
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(List.of(existingEvent));

            Event patchedMock = createGraphEvent("evt-123", "2026-11-10T10:00:00", "2026-11-10T11:00:00");
            patchedMock.setSubject("Titolo Aggiornato");
            when(calendarGateway.aggiornaEvento(eq(SALA_TEST), eq("evt-123"), any())).thenReturn(patchedMock);

            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Titolo Aggiornato")
                    .start("2026-11-10T10:00:00")
                    .end("2026-11-10T11:00:00")
                    .tipoModifica("SINGOLA")
                    .build();

            PrenotazioneDTO result = prenotazioniService.modificaPrenotazione("evt-123", request, createAdminJwt());
            assertThat(result).isNotNull();
            assertThat(result.getTitolo()).isEqualTo("Titolo Aggiornato");
        }
    }

    @Nested
    @DisplayName("2. Regole di Fascia Oraria Aziendale (08:00 - 20:00)")
    class BusinessHoursValidationTests {

        @BeforeEach
        void setUpBusinessHoursMocks() {
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(Collections.emptyList());

            Event mockCreated = createGraphEvent("evt-mock-created", "2026-11-10T08:00:00", "2026-11-10T09:00:00");
            when(calendarGateway.creaEvento(eq(SALA_TEST), any())).thenReturn(mockCreated);
        }

        @Test
        @DisplayName("Dovrebbe consentire prenotazioni perfettamente all'interno della fascia oraria consentita (08:00 - 20:00)")
        void testBusinessHours_ValidSlotAtBoundary_0800To2000_Allowed() {
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(Collections.emptyList());

            Event mockEvt1 = createGraphEvent("evt-mattina", "2026-11-10T08:00:00", "2026-11-10T09:00:00");
            when(calendarGateway.creaEvento(eq(SALA_TEST), any())).thenReturn(mockEvt1);

            // 08:00 -> 09:00 (limite inferiore consentito)
            PrenotazioneRequestDTO reqMattina = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Meeting Mattutino")
                    .start("2026-11-10T08:00:00")
                    .end("2026-11-10T09:00:00")
                    .build();

            PrenotazioneDTO res1 = prenotazioniService.creaPrenotazione(reqMattina, createAdminJwt());
            assertThat(res1).isNotNull();

            Event mockEvt2 = createGraphEvent("evt-sera", "2026-11-10T19:00:00", "2026-11-10T20:00:00");
            when(calendarGateway.creaEvento(eq(SALA_TEST), any())).thenReturn(mockEvt2);

            // 19:00 -> 20:00 (limite superiore consentito)
            PrenotazioneRequestDTO reqSera = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Meeting Serale")
                    .start("2026-11-10T19:00:00")
                    .end("2026-11-10T20:00:00")
                    .build();

            PrenotazioneDTO res2 = prenotazioniService.creaPrenotazione(reqSera, createAdminJwt());
            assertThat(res2).isNotNull();
        }

        @Test
        @DisplayName("Verifica blocco fascia oraria: dovrebbe rifiutare prenotazioni prima delle 08:00 del mattino")
        void testBusinessHours_BookingBefore0800_ShouldBeRejected() {
            // Richiesta: 06:30 -> 07:30 (fuori dalla fascia 08:00-20:00)
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Meeting Troppo Presto")
                    .start("2026-11-10T06:30:00")
                    .end("2026-11-10T07:30:00")
                    .build();

            // NOTA DI PROGETTAZIONE: Il requisito prescrive il blocco fuori dalla fascia 08:00 - 20:00.
            // Se il codice di produzione non convalida il limite orario aziendale, questo test fallisce
            // evidenziando la mancata applicazione della policy aziendale (R1/R7).
            assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt()),
                    "L'applicazione dovrebbe respingere le prenotazioni precedenti alle 08:00 con IllegalArgumentException"
            );
        }

        @Test
        @DisplayName("Verifica blocco fascia oraria: dovrebbe rifiutare prenotazioni che iniziano prima delle 08:00 e terminano dopo")
        void testBusinessHours_BookingStartingBefore0800_ShouldBeRejected() {
            // Richiesta: 07:30 -> 09:00
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Meeting A Cavallo Inizio Fascia")
                    .start("2026-11-10T07:30:00")
                    .end("2026-11-10T09:00:00")
                    .build();

            assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt()),
                    "L'applicazione dovrebbe respingere prenotazioni che iniziano prima delle 08:00"
            );
        }

        @Test
        @DisplayName("Verifica blocco fascia oraria: dovrebbe rifiutare prenotazioni che terminano dopo le 20:00")
        void testBusinessHours_BookingEndingAfter2000_ShouldBeRejected() {
            // Richiesta: 19:30 -> 21:00 (termina oltre le 20:00)
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Meeting Oltre Orario Chiusura")
                    .start("2026-11-10T19:30:00")
                    .end("2026-11-10T21:00:00")
                    .build();

            assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt()),
                    "L'applicazione dovrebbe respingere prenotazioni che si protraggono oltre le 20:00"
            );
        }

        @Test
        @DisplayName("Verifica blocco fascia oraria: dovrebbe rifiutare prenotazioni interamente notturne (es. 22:00 -> 23:00)")
        void testBusinessHours_BookingNightTime_ShouldBeRejected() {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Meeting Notturno")
                    .start("2026-11-10T22:00:00")
                    .end("2026-11-10T23:00:00")
                    .build();

            assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt()),
                    "L'applicazione dovrebbe respingere prenotazioni in orario notturno"
            );
        }
    }

    @Nested
    @DisplayName("3. Validazione Slot Temporali e Gestione Timezone (Europe/Rome vs UTC)")
    class TimezoneAndSlotValidationTests {

        @Test
        @DisplayName("Dovrebbe rifiutare prenotazioni dove l'orario di fine è antecedente all'orario di inizio")
        void testSlotValidation_EndBeforeStart_ThrowsException() {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Inversione Temporale")
                    .start("2026-11-10T11:00:00")
                    .end("2026-11-10T10:00:00")
                    .build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt())
            );

            assertThat(ex.getMessage()).contains("successivo all'orario di inizio");
        }

        @Test
        @DisplayName("Dovrebbe rifiutare prenotazioni dove l'orario di fine coincide esattamente con l'orario di inizio")
        void testSlotValidation_EndEqualsStart_ThrowsException() {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Durata Zero")
                    .start("2026-11-10T10:00:00")
                    .end("2026-11-10T10:00:00")
                    .build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt())
            );

            assertThat(ex.getMessage()).contains("successivo all'orario di inizio");
        }

        @Test
        @DisplayName("Dovrebbe rifiutare formati data/ora non conformi allo standard ISO-8601")
        void testSlotValidation_MalformedDateFormat_ThrowsException() {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Formato Errato")
                    .start("10/11/2026 10:00")
                    .end("10/11/2026 11:00")
                    .build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    prenotazioniService.creaPrenotazione(request, createAdminJwt())
            );

            assertThat(ex.getMessage()).contains("Formato data/ora non valido");
        }

        @Test
        @DisplayName("Dovrebbe impostare esplicitamente la timezone 'Europe/Rome' nell'evento Microsoft Graph creato")
        void testTimezone_GraphEventConfiguredWithEuropeRomeTimezone() {
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(Collections.emptyList());

            Event mockCreated = createGraphEvent("evt-tz-check", "2026-11-10T09:00:00", "2026-11-10T10:00:00");
            when(calendarGateway.creaEvento(eq(SALA_TEST), any())).thenReturn(mockCreated);

            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .titolo("Meeting Timezone Check")
                    .start("2026-11-10T09:00:00")
                    .end("2026-11-10T10:00:00")
                    .build();

            prenotazioniService.creaPrenotazione(request, createAdminJwt());

            ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
            verify(calendarGateway).creaEvento(eq(SALA_TEST), eventCaptor.capture());

            Event captured = eventCaptor.getValue();
            assertThat(captured).isNotNull();
            assertThat(captured.getStart()).isNotNull();
            assertThat(captured.getStart().getTimeZone()).isEqualTo("Europe/Rome");
            assertThat(captured.getEnd()).isNotNull();
            assertThat(captured.getEnd().getTimeZone()).isEqualTo("Europe/Rome");
        }

        @Test
        @DisplayName("Dovrebbe consentire timestamp UTC validi (con 'Z') o con offset nei filtri temporali di getPrenotazioni")
        void testTimezone_UtcAndOffsetAllowedInGetPrenotazioniFilters() {
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(Collections.emptyList());

            // Con suffisso UTC 'Z'
            List<PrenotazioneDTO> resUtc = prenotazioniService.getPrenotazioni(
                    SALA_TEST,
                    "2026-11-10T08:00:00Z",
                    "2026-11-10T18:00:00Z",
                    createAdminJwt()
            );
            assertThat(resUtc).isNotNull();

            // Con offset +01:00 (W. Europe Standard Time / Rome solar time)
            List<PrenotazioneDTO> resOffset = prenotazioniService.getPrenotazioni(
                    SALA_TEST,
                    "2026-11-10T08:00:00+01:00",
                    "2026-11-10T18:00:00+01:00",
                    createAdminJwt()
            );
            assertThat(resOffset).isNotNull();
        }
    }

    @Nested
    @DisplayName("4. Modifica Serie nella Stessa Sala (tipoModifica=SERIE)")
    class ModificaSerieStessaSalaTests {

        @Test
        @DisplayName("Test 1: Orario del master invariato -> l'event passato ad aggiornaEvento ha start e end a null")
        void testModificaSerie_OrarioInvariato_StartEndNull() {
            // Master esistente dalle 10:00:00 alle 11:00:00 con data 2026-09-01
            Event master = createGraphEvent("master-123", "2026-09-01T10:00:00", "2026-09-01T11:00:00");
            when(calendarGateway.getEvento(eq(SALA_TEST), eq("master-123"))).thenReturn(master);
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(Collections.emptyList());
            when(calendarGateway.aggiornaEvento(eq(SALA_TEST), eq("master-123"), any())).thenReturn(master);

            // Richiesta con stessa ora (10:00:00 -> 11:00:00) su data diversa
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .salaEmailOriginale(SALA_TEST)
                    .titolo("Meeting Serie Orario Invariato")
                    .start("2026-11-10T10:00:00")
                    .end("2026-11-10T11:00:00")
                    .tipoModifica("SERIE")
                    .seriesMasterId("master-123")
                    .build();

            prenotazioniService.modificaPrenotazione("evt-occ-1", request, createAdminJwt());

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(calendarGateway).aggiornaEvento(eq(SALA_TEST), eq("master-123"), captor.capture());

            Event captured = captor.getValue();
            assertThat(captured).isNotNull();
            assertThat(captured.getStart()).isNull();
            assertThat(captured.getEnd()).isNull();
            assertThat(captured.getSubject()).isEqualTo("Meeting Serie Orario Invariato");
        }

        @Test
        @DisplayName("Test 2: Orario del master modificato -> lo start usa data del master + orario request, end calcolato con durata")
        void testModificaSerie_OrarioModificato_CalcolaStartEndConDataMaster() {
            // Master originale: data 2026-09-01, orario 10:00:00 -> 11:00:00 (durata 60 min)
            Event master = createGraphEvent("master-123", "2026-09-01T10:00:00", "2026-09-01T11:00:00");
            when(calendarGateway.getEvento(eq(SALA_TEST), eq("master-123"))).thenReturn(master);
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(Collections.emptyList());
            when(calendarGateway.aggiornaEvento(eq(SALA_TEST), eq("master-123"), any())).thenReturn(master);

            // Richiesta con nuovo orario: 14:30:00 -> 16:00:00 (durata 90 min)
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .salaEmailOriginale(SALA_TEST)
                    .titolo("Meeting Serie Orario Modificato")
                    .start("2026-11-10T14:30:00")
                    .end("2026-11-10T16:00:00")
                    .tipoModifica("SERIE")
                    .seriesMasterId("master-123")
                    .build();

            prenotazioniService.modificaPrenotazione("evt-occ-1", request, createAdminJwt());

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(calendarGateway).aggiornaEvento(eq(SALA_TEST), eq("master-123"), captor.capture());

            Event captured = captor.getValue();
            assertThat(captured).isNotNull();
            assertThat(captured.getStart()).isNotNull();
            assertThat(captured.getStart().getDateTime()).isEqualTo("2026-09-01T14:30:00");
            assertThat(captured.getStart().getTimeZone()).isEqualTo("Europe/Rome");
            assertThat(captured.getEnd()).isNotNull();
            assertThat(captured.getEnd().getDateTime()).isEqualTo("2026-09-01T16:00:00");
            assertThat(captured.getEnd().getTimeZone()).isEqualTo("Europe/Rome");
            assertThat(captured.getSubject()).isEqualTo("Meeting Serie Orario Modificato");
        }

        @Test
        @DisplayName("Test 3: Eccezione su getEvento del master -> fallback su start ed end della request")
        void testModificaSerie_EccezioneGetEventoMaster_FallbackSuRequest() {
            when(calendarGateway.getEvento(eq(SALA_TEST), eq("master-123")))
                    .thenThrow(new RuntimeException("Graph API offline"));
            when(calendarGateway.getEventi(eq(SALA_TEST), any(), any())).thenReturn(Collections.emptyList());

            Event dummyUpdated = createGraphEvent("master-123", "2026-11-10T14:30:00", "2026-11-10T16:00:00");
            when(calendarGateway.aggiornaEvento(eq(SALA_TEST), eq("master-123"), any())).thenReturn(dummyUpdated);

            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(SALA_TEST)
                    .salaEmailOriginale(SALA_TEST)
                    .titolo("Meeting Serie Fallback")
                    .start("2026-11-10T14:30:00")
                    .end("2026-11-10T16:00:00")
                    .tipoModifica("SERIE")
                    .seriesMasterId("master-123")
                    .build();

            prenotazioniService.modificaPrenotazione("evt-occ-1", request, createAdminJwt());

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(calendarGateway).aggiornaEvento(eq(SALA_TEST), eq("master-123"), captor.capture());

            Event captured = captor.getValue();
            assertThat(captured).isNotNull();
            assertThat(captured.getStart()).isNotNull();
            assertThat(captured.getStart().getDateTime()).isEqualTo("2026-11-10T14:30:00");
            assertThat(captured.getStart().getTimeZone()).isEqualTo("Europe/Rome");
            assertThat(captured.getEnd()).isNotNull();
            assertThat(captured.getEnd().getDateTime()).isEqualTo("2026-11-10T16:00:00");
            assertThat(captured.getEnd().getTimeZone()).isEqualTo("Europe/Rome");
            assertThat(captured.getSubject()).isEqualTo("Meeting Serie Fallback");
        }
    }
}
