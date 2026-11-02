package it.szn.prenotazionesale.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.DateTimeTimeZone;
import com.microsoft.graph.models.EmailAddress;
import com.microsoft.graph.models.Event;
import com.microsoft.graph.models.Recipient;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@DisplayName("QA Smoke Test Suite — Ciclo di Vita Prenotazioni e Regole di Business Core")
public class CoreBookingFlowQASmokeTest {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private SaleService saleService;

    @MockitoBean
    private GraphCalendarGateway calendarGateway;

    private static final String SALA_EMAIL = "sala1@szn.it";
    private static final String SALA_NOME = "Sala Dohrn";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        when(saleService.getSale()).thenReturn(List.of(
                SalaDTO.builder().id(SALA_EMAIL).nome(SALA_NOME).email(SALA_EMAIL).build()
        ));
    }

    private String iso(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute, 0).format(ISO_FORMATTER);
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_RoomBooking.Admin"))
                .jwt(j -> j.claim("roles", List.of("RoomBooking.Admin"))
                        .claim("oid", "admin-user-001")
                        .claim("preferred_username", "admin.prenotazioni@szn.it"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor standardUserJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                .jwt(j -> j.claim("roles", List.of("RoomBooking.User"))
                        .claim("oid", "standard-user-002")
                        .claim("preferred_username", "mario.rossi@szn.it"));
    }

    private Event createMockGraphEvent(String id, String subject, String startIso, String endIso) {
        Event event = new Event();
        event.setId(id);
        event.setSubject(subject);

        DateTimeTimeZone start = new DateTimeTimeZone();
        start.setDateTime(startIso);
        start.setTimeZone("Europe/Rome");
        event.setStart(start);

        DateTimeTimeZone end = new DateTimeTimeZone();
        end.setDateTime(endIso);
        end.setTimeZone("Europe/Rome");
        event.setEnd(end);

        Recipient organizer = new Recipient();
        EmailAddress email = new EmailAddress();
        email.setAddress("organizer@szn.it");
        email.setName("Organizer User");
        organizer.setEmailAddress(email);
        event.setOrganizer(organizer);

        return event;
    }

    @Test
    @DisplayName("QA-SMK-01 (P0): Discovery Sale — GET /api/sale restituisce elenco sale attive con HTTP 200")
    void testQA01_DiscoverySale_ReturnsList() throws Exception {
        mockMvc.perform(get("/api/sale")
                        .with(standardUserJwt()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].email").value(SALA_EMAIL))
                .andExpect(jsonPath("$[0].nome").value(SALA_NOME));
    }

    @Test
    @DisplayName("QA-SMK-02 (P0): Lettura Calendario Singola Sala — GET /api/prenotazioni restituisce eventi con HTTP 200")
    void testQA02_GetPrenotazioniSingolaSala_ValidInterval_ReturnsOk() throws Exception {
        LocalDate futureDay = LocalDate.now().plusDays(5);
        String startIso = iso(futureDay, 8, 0);
        String endIso = iso(futureDay, 20, 0);

        Event mockEvt = createMockGraphEvent("evt-01", "Riunione Scientifica",
                iso(futureDay, 10, 0), iso(futureDay, 11, 0));

        when(calendarGateway.getEventi(eq(SALA_EMAIL), anyString(), anyString()))
                .thenReturn(List.of(mockEvt));

        mockMvc.perform(get("/api/prenotazioni")
                        .param("salaEmail", SALA_EMAIL)
                        .param("dataInizio", startIso)
                        .param("dataFine", endIso)
                        .with(standardUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("evt-01"))
                .andExpect(jsonPath("$[0].titolo").value("Riunione Scientifica"))
                .andExpect(jsonPath("$[0].modificabile").value(false));
    }

    @Test
    @DisplayName("QA-SMK-03 (P0): Tabellone Globale — GET /api/prenotazioni/tutte restituisce struttura aggregata e parziale=false")
    void testQA03_GetPrenotazioniTutteSale_GlobalCalendar_ReturnsStructuredResponse() throws Exception {
        LocalDate futureDay = LocalDate.now().plusDays(5);
        String startIso = iso(futureDay, 8, 0);
        String endIso = iso(futureDay, 20, 0);

        Event mockEvt = createMockGraphEvent("evt-02", "Workshop Interno",
                iso(futureDay, 14, 0), iso(futureDay, 15, 0));

        when(calendarGateway.getEventi(eq(SALA_EMAIL), anyString(), anyString()))
                .thenReturn(List.of(mockEvt));

        mockMvc.perform(get("/api/prenotazioni/tutte")
                        .param("dataInizio", startIso)
                        .param("dataFine", endIso)
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parziale").value(false))
                .andExpect(jsonPath("$.prenotazioni[0].id").value("evt-02"))
                .andExpect(jsonPath("$.prenotazioni[0].modificabile").value(true))
                .andExpect(jsonPath("$.saleNonDisponibili").isArray())
                .andExpect(jsonPath("$.saleNonDisponibili").isEmpty());
    }

    @Test
    @DisplayName("QA-SMK-04 (P0): Creazione Prenotazione Admin — POST /api/prenotazioni crea evento valido con HTTP 200")
    void testQA04_CreaPrenotazione_Admin_ValidSlot_ReturnsOkWithId() throws Exception {
        LocalDate futureDay = LocalDate.now().plusDays(5);
        String startIso = iso(futureDay, 10, 0);
        String endIso = iso(futureDay, 11, 0);

        PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                .salaEmail(SALA_EMAIL)
                .titolo("Meeting Progetto Europeo")
                .descrizione("Discussione milestone 3")
                .start(startIso)
                .end(endIso)
                .build();

        when(calendarGateway.getEventi(eq(SALA_EMAIL), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        Event createdEvent = createMockGraphEvent("new-evt-100", "Meeting Progetto Europeo", startIso, endIso);
        when(calendarGateway.creaEvento(eq(SALA_EMAIL), any(Event.class))).thenReturn(createdEvent);

        mockMvc.perform(post("/api/prenotazioni")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("new-evt-100"))
                .andExpect(jsonPath("$.titolo").value("Meeting Progetto Europeo"));

        verify(calendarGateway, times(1)).creaEvento(eq(SALA_EMAIL), any(Event.class));
    }

    @Test
    @DisplayName("QA-SMK-05 (P0): Controllo RBAC Creazione — POST /api/prenotazioni da utente non-admin restituisce 403 Forbidden")
    void testQA05_CreaPrenotazione_StandardUser_ReturnsForbidden() throws Exception {
        LocalDate futureDay = LocalDate.now().plusDays(5);
        PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                .salaEmail(SALA_EMAIL)
                .titolo("Meeting Non Autorizzato")
                .start(iso(futureDay, 10, 0))
                .end(iso(futureDay, 11, 0))
                .build();

        mockMvc.perform(post("/api/prenotazioni")
                        .with(standardUserJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verify(calendarGateway, never()).creaEvento(anyString(), any(Event.class));
    }

    @Test
    @DisplayName("QA-SMK-06 (P0): Modifica Prenotazione — PATCH /api/prenotazioni/{id} aggiorna orario con HTTP 200")
    void testQA06_ModificaPrenotazione_Admin_ReturnsOk() throws Exception {
        String eventId = "evt-to-patch";
        LocalDate futureDay = LocalDate.now().plusDays(6);
        String oldStart = iso(futureDay, 10, 0);
        String oldEnd = iso(futureDay, 11, 0);
        String newStart = iso(futureDay, 14, 0);
        String newEnd = iso(futureDay, 15, 0);

        Event existing = createMockGraphEvent(eventId, "Titolo Iniziale", oldStart, oldEnd);
        when(calendarGateway.getEvento(eq(SALA_EMAIL), eq(eventId), any())).thenReturn(existing);
        when(calendarGateway.getEventi(eq(SALA_EMAIL), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        Event patched = createMockGraphEvent(eventId, "Titolo Aggiornato", newStart, newEnd);
        when(calendarGateway.aggiornaEvento(eq(SALA_EMAIL), eq(eventId), any(Event.class))).thenReturn(patched);

        PrenotazioneRequestDTO updateRequest = PrenotazioneRequestDTO.builder()
                .salaEmail(SALA_EMAIL)
                .titolo("Titolo Aggiornato")
                .start(newStart)
                .end(newEnd)
                .tipoModifica("SINGOLA")
                .build();

        mockMvc.perform(patch("/api/prenotazioni/" + eventId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId))
                .andExpect(jsonPath("$.titolo").value("Titolo Aggiornato"));

        verify(calendarGateway, times(1)).aggiornaEvento(eq(SALA_EMAIL), eq(eventId), any(Event.class));
    }

    @Test
    @DisplayName("QA-SMK-07 (P0): Conflitto Overlap — POST /api/prenotazioni su slot occupato rifiutato con HTTP 400 Bad Request")
    void testQA07_CreaPrenotazione_ConflictOverlap_Returns400BadRequest() throws Exception {
        LocalDate futureDay = LocalDate.now().plusDays(5);
        String existingStart = iso(futureDay, 10, 0);
        String existingEnd = iso(futureDay, 11, 0);

        Event existingEvent = createMockGraphEvent("existing-1", "Prenotazione Concorrente", existingStart, existingEnd);
        when(calendarGateway.getEventi(eq(SALA_EMAIL), anyString(), anyString()))
                .thenReturn(List.of(existingEvent));

        // Slot sovrapposto: 10:30 - 11:30
        PrenotazioneRequestDTO overlappingRequest = PrenotazioneRequestDTO.builder()
                .salaEmail(SALA_EMAIL)
                .titolo("Nuovo Meeting Confliggente")
                .start(iso(futureDay, 10, 30))
                .end(iso(futureDay, 11, 30))
                .build();

        mockMvc.perform(post("/api/prenotazioni")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overlappingRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("occupata")));

        verify(calendarGateway, never()).creaEvento(anyString(), any(Event.class));
    }

    @Test
    @DisplayName("QA-SMK-08 (P0): Cancellazione Default SINGOLA — DELETE /api/prenotazioni/{id} restituisce HTTP 204 No Content")
    void testQA08_CancellaPrenotazione_Admin_DefaultSingola_Returns204NoContent() throws Exception {
        String eventId = "evt-to-delete";
        LocalDate futureDay = LocalDate.now().plusDays(5);

        Event existing = createMockGraphEvent(eventId, "Da Eliminare",
                iso(futureDay, 10, 0), iso(futureDay, 11, 0));
        when(calendarGateway.getEvento(eq(SALA_EMAIL), eq(eventId), any())).thenReturn(existing);

        mockMvc.perform(delete("/api/prenotazioni/" + eventId)
                        .param("salaEmail", SALA_EMAIL)
                        .with(adminJwt()))
                .andExpect(status().isNoContent());

        verify(calendarGateway, times(1)).eliminaEvento(eq(SALA_EMAIL), eq(eventId));
    }

    @Test
    @DisplayName("QA-SMK-09 (P1): Orari Fuori Fascia Aziendale — POST /api/prenotazioni prima delle 08:00 rifiutato con HTTP 400")
    void testQA09_CreaPrenotazione_OutsideBusinessHours_Returns400BadRequest() throws Exception {
        LocalDate futureDay = LocalDate.now().plusDays(5);
        PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                .salaEmail(SALA_EMAIL)
                .titolo("Meeting All'Alba")
                .start(iso(futureDay, 6, 30))
                .end(iso(futureDay, 7, 30))
                .build();

        mockMvc.perform(post("/api/prenotazioni")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("08:00 - 20:00")));
    }

    @Test
    @DisplayName("QA-SMK-10 (P1): Prenotazione nel Passato — POST /api/prenotazioni rifiutato con HTTP 400 Bad Request")
    void testQA10_CreaPrenotazione_InThePast_Returns400BadRequest() throws Exception {
        PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                .salaEmail(SALA_EMAIL)
                .titolo("Meeting Storico")
                .start("2020-01-10T10:00:00")
                .end("2020-01-10T11:00:00")
                .build();

        mockMvc.perform(post("/api/prenotazioni")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("trascorsi")));
    }

    @Test
    @DisplayName("QA-SMK-11 (P1): Sanitizzazione Input / Anti-XSS — POST con payload malevolo rifiutato con HTTP 400")
    void testQA11_InputSanitization_XSSPayload_Returns400BadRequest() throws Exception {
        LocalDate futureDay = LocalDate.now().plusDays(5);
        PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                .salaEmail(SALA_EMAIL)
                .titolo("<script>alert('xss-titolo')</script>Riunione")
                .start(iso(futureDay, 10, 0))
                .end(iso(futureDay, 11, 0))
                .build();

        mockMvc.perform(post("/api/prenotazioni")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("tag HTML o codice script non consentito")));
    }
}
