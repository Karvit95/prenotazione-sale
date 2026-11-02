package it.szn.prenotazionesale.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.DateTimeTimeZone;
import com.microsoft.graph.models.Event;
import it.szn.prenotazionesale.graph.GraphCalendarGateway;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.model.SalaDTO;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@DisplayName("E2E Test Suite — Percorsi Utente e Cicli di Vita Completi (Multi-Step Stateful)")
public class CompleteBookingLifecycleE2ETest {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private SaleService saleService;

    @MockitoBean
    private GraphCalendarGateway calendarGateway;

    private static final String SALA_1 = "sala1@szn.it";
    private static final String SALA_1_NOME = "Sala Dohrn";

    private static final String SALA_2 = "sala2@szn.it";
    private static final String SALA_2_NOME = "Sala Medusa";

    // Simulatore di storage stateful in-memory per le caselle di posta Exchange
    private final Map<String, List<Event>> statefulCalendarStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        statefulCalendarStore.clear();
        statefulCalendarStore.put(SALA_1, new CopyOnWriteArrayList<>());
        statefulCalendarStore.put(SALA_2, new CopyOnWriteArrayList<>());

        when(saleService.getSale()).thenReturn(List.of(
                SalaDTO.builder().id(SALA_1).nome(SALA_1_NOME).email(SALA_1).build(),
                SalaDTO.builder().id(SALA_2).nome(SALA_2_NOME).email(SALA_2).build()
        ));

        // Cablaggio stateful del mock gateway su Graph
        when(calendarGateway.getEventi(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String email = invocation.getArgument(0);
            String startStr = invocation.getArgument(1);
            String endStr = invocation.getArgument(2);
            LocalDateTime queryStart = LocalDateTime.parse(startStr.substring(0, 19));
            LocalDateTime queryEnd = LocalDateTime.parse(endStr.substring(0, 19));

            List<Event> events = statefulCalendarStore.getOrDefault(email, Collections.emptyList());
            List<Event> matched = new ArrayList<>();
            for (Event ev : events) {
                if (ev.getStart() != null && ev.getEnd() != null) {
                    LocalDateTime evStart = LocalDateTime.parse(ev.getStart().getDateTime().substring(0, 19));
                    LocalDateTime evEnd = LocalDateTime.parse(ev.getEnd().getDateTime().substring(0, 19));
                    // Overlap check per la calendarView
                    if (evStart.isBefore(queryEnd) && evEnd.isAfter(queryStart)) {
                        matched.add(ev);
                    }
                }
            }
            return matched;
        });

        when(calendarGateway.getEvento(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String email = invocation.getArgument(0);
            String eventId = invocation.getArgument(1);
            return statefulCalendarStore.getOrDefault(email, Collections.emptyList()).stream()
                    .filter(e -> Objects.equals(e.getId(), eventId))
                    .findFirst()
                    .orElse(null);
        });

        when(calendarGateway.creaEvento(anyString(), any(Event.class))).thenAnswer(invocation -> {
            String email = invocation.getArgument(0);
            Event toCreate = invocation.getArgument(1);
            if (toCreate.getId() == null || toCreate.getId().isBlank()) {
                toCreate.setId("graph-evt-" + UUID.randomUUID());
            }
            statefulCalendarStore.computeIfAbsent(email, k -> new CopyOnWriteArrayList<>()).add(toCreate);
            return toCreate;
        });

        when(calendarGateway.aggiornaEvento(anyString(), anyString(), any(Event.class))).thenAnswer(invocation -> {
            String email = invocation.getArgument(0);
            String eventId = invocation.getArgument(1);
            Event patch = invocation.getArgument(2);

            List<Event> list = statefulCalendarStore.getOrDefault(email, Collections.emptyList());
            for (int i = 0; i < list.size(); i++) {
                Event existing = list.get(i);
                if (Objects.equals(existing.getId(), eventId)) {
                    if (patch.getSubject() != null) existing.setSubject(patch.getSubject());
                    if (patch.getStart() != null) existing.setStart(patch.getStart());
                    if (patch.getEnd() != null) existing.setEnd(patch.getEnd());
                    return existing;
                }
            }
            patch.setId(eventId);
            list.add(patch);
            return patch;
        });

        doAnswer(invocation -> {
            String email = invocation.getArgument(0);
            String eventId = invocation.getArgument(1);
            List<Event> list = statefulCalendarStore.get(email);
            if (list != null) {
                list.removeIf(e -> Objects.equals(e.getId(), eventId));
            }
            return null;
        }).when(calendarGateway).eliminaEvento(anyString(), anyString());
    }

    private String iso(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute, 0).format(ISO_FORMATTER);
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_RoomBooking.Admin"))
                .jwt(j -> j.claim("roles", List.of("RoomBooking.Admin"))
                        .claim("oid", "admin-e2e-001")
                        .claim("preferred_username", "admin.e2e@szn.it"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor standardUserJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                .jwt(j -> j.claim("roles", List.of("RoomBooking.User"))
                        .claim("oid", "user-e2e-002")
                        .claim("preferred_username", "ricercatore.e2e@szn.it"));
    }

    @Test
    @DisplayName("E2E-01 (P0): Percorso Completo Ciclo di Vita Prenotazione — Discovery -> Creazione -> Rilevamento -> Modifica -> Cancellazione")
    void testE2E01_FullBookingLifecycleJourney() throws Exception {
        LocalDate testDay = LocalDate.now().plusDays(10);
        String dayStart = iso(testDay, 8, 0);
        String dayEnd = iso(testDay, 20, 0);

        // PASSO 1: Discovery delle sale disponibili
        mockMvc.perform(get("/api/sale").with(standardUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value(SALA_1))
                .andExpect(jsonPath("$[1].email").value(SALA_2));

        // PASSO 2: Verifica iniziale che la sala sia libera per la giornata
        mockMvc.perform(get("/api/prenotazioni")
                        .param("salaEmail", SALA_1)
                        .param("dataInizio", dayStart)
                        .param("dataFine", dayEnd)
                        .with(standardUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        // PASSO 3: Creazione nuova prenotazione dalle 10:00 alle 11:00 da parte dell'Admin
        String startSlot1 = iso(testDay, 10, 0);
        String endSlot1 = iso(testDay, 11, 0);
        PrenotazioneRequestDTO createDto = PrenotazioneRequestDTO.builder()
                .salaEmail(SALA_1)
                .titolo("E2E Meeting Iniziale")
                .descrizione("Discussione progetto Alpha")
                .start(startSlot1)
                .end(endSlot1)
                .build();

        MvcResult createResult = mockMvc.perform(post("/api/prenotazioni")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.titolo").value("E2E Meeting Iniziale"))
                .andReturn();

        String responseBody = createResult.getResponse().getContentAsString();
        String createdId = objectMapper.readTree(responseBody).get("id").asText();
        assertThat(createdId).isNotBlank();

        // PASSO 4: Verifica che l'evento sia ora visibile nel calendario della sala
        mockMvc.perform(get("/api/prenotazioni")
                        .param("salaEmail", SALA_1)
                        .param("dataInizio", dayStart)
                        .param("dataFine", dayEnd)
                        .with(standardUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(createdId))
                .andExpect(jsonPath("$[0].titolo").value("E2E Meeting Iniziale"));

        // PASSO 5: Modifica della prenotazione (riprogrammazione dalle 14:00 alle 15:30)
        String newStartSlot = iso(testDay, 14, 0);
        String newEndSlot = iso(testDay, 15, 30);
        PrenotazioneRequestDTO updateDto = PrenotazioneRequestDTO.builder()
                .salaEmail(SALA_1)
                .titolo("E2E Meeting Riprogrammato")
                .descrizione("Orario pomeridiano concordato")
                .start(newStartSlot)
                .end(newEndSlot)
                .tipoModifica("SINGOLA")
                .build();

        mockMvc.perform(patch("/api/prenotazioni/" + createdId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdId))
                .andExpect(jsonPath("$.titolo").value("E2E Meeting Riprogrammato"));

        // PASSO 6: Verifica che lo slot mattutino 10:00-11:00 sia tornato libero per una nuova prenotazione
        PrenotazioneRequestDTO morningSlotDto = PrenotazioneRequestDTO.builder()
                .salaEmail(SALA_1)
                .titolo("Nuovo Meeting Mattutino")
                .start(startSlot1)
                .end(endSlot1)
                .build();

        mockMvc.perform(post("/api/prenotazioni")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(morningSlotDto)))
                .andExpect(status().isOk());

        // PASSO 7: Cancellazione dell'evento riprogrammato
        mockMvc.perform(delete("/api/prenotazioni/" + createdId)
                        .param("salaEmail", SALA_1)
                        .with(adminJwt()))
                .andExpect(status().isNoContent());

        // PASSO 8: Verifica che l'evento riprogrammato non sia più presente nel calendario
        mockMvc.perform(get("/api/prenotazioni")
                        .param("salaEmail", SALA_1)
                        .param("dataInizio", dayStart)
                        .param("dataFine", dayEnd)
                        .with(standardUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + createdId + "')]").isEmpty());
    }

    @Test
    @DisplayName("E2E-02 (P0): Percorso Concorrenza e Risoluzione Conflitti — Slot occupato -> 400 Bad Request -> Rettifica -> Successo contiguo")
    void testE2E02_ConcurrentCollisionAndRecoveryJourney() throws Exception {
        LocalDate testDay = LocalDate.now().plusDays(12);

        // PASSO 1: Utente A prenota slot 10:00 - 11:00
        PrenotazioneRequestDTO bookingA = PrenotazioneRequestDTO.builder()
                .salaEmail(SALA_1)
                .titolo("Meeting Utente A")
                .start(iso(testDay, 10, 0))
                .end(iso(testDay, 11, 0))
                .build();

        mockMvc.perform(post("/api/prenotazioni")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bookingA)))
                .andExpect(status().isOk());

        // PASSO 2: Utente B tenta uno slot parzialmente sovrapposto (10:30 - 11:30)
        PrenotazioneRequestDTO conflictingB = PrenotazioneRequestDTO.builder()
                .salaEmail(SALA_1)
                .titolo("Meeting Utente B (Conflitto)")
                .start(iso(testDay, 10, 30))
                .end(iso(testDay, 11, 30))
                .build();

        mockMvc.perform(post("/api/prenotazioni")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflictingB)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("occupata")));

        // PASSO 3: Utente B rettifica l'orario prenotando subito dopo (11:00 - 12:00, adiacente e lecita)
        PrenotazioneRequestDTO adjustedB = PrenotazioneRequestDTO.builder()
                .salaEmail(SALA_1)
                .titolo("Meeting Utente B (Rettificato)")
                .start(iso(testDay, 11, 0))
                .end(iso(testDay, 12, 0))
                .build();

        mockMvc.perform(post("/api/prenotazioni")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adjustedB)))
                .andExpect(status().isOk());

        // PASSO 4: Verifica che entrambe le prenotazioni coesistano contigue senza sovrapposizione
        mockMvc.perform(get("/api/prenotazioni")
                        .param("salaEmail", SALA_1)
                        .param("dataInizio", iso(testDay, 8, 0))
                        .param("dataFine", iso(testDay, 20, 0))
                        .with(standardUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].titolo").value("Meeting Utente A"))
                .andExpect(jsonPath("$[1].titolo").value("Meeting Utente B (Rettificato)"));
    }

    @Test
    @DisplayName("E2E-03 (P0): Percorso Resilienza Tabellone Globale con Degrado Parziale e Recupero Successivo")
    void testE2E03_GlobalDashboardDegradationAndRecoveryJourney() throws Exception {
        LocalDate testDay = LocalDate.now().plusDays(15);
        String dayStart = iso(testDay, 8, 0);
        String dayEnd = iso(testDay, 20, 0);

        // Prepopoliamo la sala 1 con 1 prenotazione
        Event evSala1 = new Event();
        evSala1.setId("evt-sala1-ok");
        evSala1.setSubject("Conferenza Stampa Dohrn");
        DateTimeTimeZone s = new DateTimeTimeZone();
        s.setDateTime(iso(testDay, 10, 0));
        evSala1.setStart(s);
        DateTimeTimeZone e = new DateTimeTimeZone();
        e.setDateTime(iso(testDay, 11, 0));
        evSala1.setEnd(e);
        statefulCalendarStore.get(SALA_1).add(evSala1);

        // Simuliamo timeout temporaneo su Sala 2
        when(calendarGateway.getEventi(eq(SALA_2), anyString(), anyString()))
                .thenThrow(new RuntimeException("Simulated Timeout", new TimeoutException("Graph timeout")));

        // PASSO 1: Richiesta globale in stato degradato
        mockMvc.perform(get("/api/prenotazioni/tutte")
                        .param("dataInizio", dayStart)
                        .param("dataFine", dayEnd)
                        .with(standardUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parziale").value(true))
                .andExpect(jsonPath("$.prenotazioni.length()").value(1))
                .andExpect(jsonPath("$.prenotazioni[0].id").value("evt-sala1-ok"))
                .andExpect(jsonPath("$.saleNonDisponibili[0].salaEmail").value(SALA_2))
                .andExpect(jsonPath("$.saleNonDisponibili[0].motivo").value("TIMEOUT"));

        // PASSO 2: Ripristino connettività Sala 2 e ri-esecuzione (es. utente clicca "Riprova")
        when(calendarGateway.getEventi(eq(SALA_2), anyString(), anyString())).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/prenotazioni/tutte")
                        .param("dataInizio", dayStart)
                        .param("dataFine", dayEnd)
                        .with(standardUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parziale").value(false))
                .andExpect(jsonPath("$.prenotazioni.length()").value(1))
                .andExpect(jsonPath("$.saleNonDisponibili").isEmpty());
    }

    @Test
    @DisplayName("E2E-04 (P1): Percorso Spostamento Sala — Prenotazione in Sala 1 riprogrammata con successo in Sala 2")
    void testE2E04_RoomChangeRelocationJourney() throws Exception {
        LocalDate testDay = LocalDate.now().plusDays(18);
        String startIso = iso(testDay, 15, 0);
        String endIso = iso(testDay, 16, 0);

        // PASSO 1: Creazione prenotazione in Sala 1
        PrenotazioneRequestDTO initialReq = PrenotazioneRequestDTO.builder()
                .salaEmail(SALA_1)
                .titolo("Meeting Spostamento")
                .start(startIso)
                .end(endIso)
                .build();

        MvcResult createRes = mockMvc.perform(post("/api/prenotazioni")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initialReq)))
                .andExpect(status().isOk())
                .andReturn();

        String eventId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        // PASSO 2: Modifica con spostamento verso Sala 2
        PrenotazioneRequestDTO moveReq = PrenotazioneRequestDTO.builder()
                .salaEmail(SALA_2)
                .salaEmailOriginale(SALA_1)
                .titolo("Meeting Spostamento - Sala Nuova")
                .start(startIso)
                .end(endIso)
                .tipoModifica("SINGOLA")
                .build();

        mockMvc.perform(patch("/api/prenotazioni/" + eventId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(moveReq)))
                .andExpect(status().isOk());

        // PASSO 3: Verifica che in Sala 2 sia presente e in Sala 1 non sia più presente
        mockMvc.perform(get("/api/prenotazioni")
                        .param("salaEmail", SALA_2)
                        .param("dataInizio", iso(testDay, 8, 0))
                        .param("dataFine", iso(testDay, 20, 0))
                        .with(standardUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].titolo").value("Meeting Spostamento - Sala Nuova"));

        mockMvc.perform(get("/api/prenotazioni")
                        .param("salaEmail", SALA_1)
                        .param("dataInizio", iso(testDay, 8, 0))
                        .param("dataFine", iso(testDay, 20, 0))
                        .with(standardUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}
