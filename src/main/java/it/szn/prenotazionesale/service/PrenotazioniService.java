package it.szn.prenotazionesale.service;

import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.model.SalaDTO;
import lombok.AllArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AllArgsConstructor
@Service
public class PrenotazioniService {

    private final GraphServiceClient graphClient;
    private final SaleService saleService;

    public List<PrenotazioneDTO> getPrenotazioni(String salaEmail, String dataInizio, String dataFine, Jwt jwt) {
        String filter = String.format(
                "start/dateTime ge '%s' and end/dateTime le '%s'",
                dataInizio, dataFine
        );

        return graphClient.users()
                .byUserId(salaEmail)
                .calendar()
                .events()
                .get(req -> {
                    req.queryParameters.filter = filter;
                    req.queryParameters.top = 500; 
                })
                .getValue()
                .stream()
                .map(event -> mapToDTO(event, salaEmail, jwt))
                .collect(Collectors.toList());
    }
    
    public List<PrenotazioneDTO> getPrenotazioniTutteSale(String dataInizio, String dataFine, Jwt jwt) {
        List<SalaDTO> tutteLeSale = saleService.getSale(); 

        return tutteLeSale.stream()  // <-- CAMBIATO: parallelStream() → stream()
                .<PrenotazioneDTO>flatMap(sala -> {
                    try {
                        List<PrenotazioneDTO> lista = getPrenotazioni(sala.getEmail(), dataInizio, dataFine, jwt);
                        return lista.stream();
                    } catch (Exception e) {
                        System.err.println("Errore nel recupero prenotazioni per la sala: " + sala.getEmail() + " - " + e.getMessage());
                        return Stream.<PrenotazioneDTO>empty();
                    }
                })
                .collect(Collectors.toList());
    }

    public PrenotazioneDTO creaPrenotazione(PrenotazioneRequestDTO request, Jwt jwt) {
<<<<<<< Updated upstream
        validaOrariPrenotazione(request.getStart(), request.getEnd());
        
=======
        verificaAdmin(jwt);  // <-- AGGIUNTO: solo admin può creare

>>>>>>> Stashed changes
        var event = buildEvent(request);

        var createdEvent = graphClient.users()
                .byUserId(request.getSalaEmail())
                .calendar()
                .events()
                .post(event);

        return mapToDTO(createdEvent, request.getSalaEmail(), jwt);
    }

    public PrenotazioneDTO modificaPrenotazione(String eventId, PrenotazioneRequestDTO request, Jwt jwt) {
<<<<<<< Updated upstream
        validaOrariPrenotazione(request.getStart(), request.getEnd());
        
=======
        verificaAdmin(jwt);  // <-- AGGIUNTO: solo admin può modificare

>>>>>>> Stashed changes
        var event = buildEvent(request);

        var updatedEvent = graphClient.users()
                .byUserId(request.getSalaEmail())
                .events()
                .byEventId(eventId)
                .patch(event);

        return mapToDTO(updatedEvent, request.getSalaEmail(), jwt);
    }

    public void cancellaPrenotazione(String eventId, String salaEmail, Jwt jwt) {
        verificaAdmin(jwt);  // <-- CAMBIATO: solo admin, rimosso controllo organizzatore rotto

        graphClient.users()
                .byUserId(salaEmail)
                .events()
                .byEventId(eventId)
                .delete();
    }

    // --- Metodi privati ---

    private Event buildEvent(PrenotazioneRequestDTO request) {
        var event = new Event();
        event.setSubject(request.getTitolo());

        if (request.getDescrizione() != null) {
            var body = new ItemBody();
            body.setContent(request.getDescrizione());
            body.setContentType(BodyType.Text);
            event.setBody(body);
        }

        var start = new DateTimeTimeZone();
        start.setDateTime(request.getStart());
        start.setTimeZone("Europe/Rome");
        event.setStart(start);

        var end = new DateTimeTimeZone();
        end.setDateTime(request.getEnd());
        end.setTimeZone("Europe/Rome");
        event.setEnd(end);

        return event;
    }

    private PrenotazioneDTO mapToDTO(Event event, String salaEmail, Jwt jwt) {
        boolean isAdmin = isAdmin(jwt);  // <-- SEMPLIFICATO

        return PrenotazioneDTO.builder()
                .id(event.getId())
                .salaId(salaEmail != null ? salaEmail.trim().toLowerCase() : null)
                .salaEmail(salaEmail)
                .salaId(salaEmail)  // <-- AGGIUNTO: popolato con l'email della sala
                .titolo(event.getSubject())
                .descrizione(event.getBody() != null ? event.getBody().getContent() : null)
                .start(event.getStart() != null ? normalizzaData(event.getStart().getDateTime()) : null)
                .end(event.getEnd() != null ? normalizzaData(event.getEnd().getDateTime()) : null)
                
                .organizzatoreNome(event.getOrganizer() != null
                        && event.getOrganizer().getEmailAddress() != null
                        ? event.getOrganizer().getEmailAddress().getName()
                        : null)
                .modificabile(isAdmin)  // <-- CAMBIATO: solo admin può modificare
                .build();
    }
<<<<<<< Updated upstream
    
    private String normalizzaData(String dateTime) {
        if (dateTime == null) return null;
        return dateTime.endsWith("Z") ? dateTime : dateTime + "Z";
    }
    
    private void validaOrariPrenotazione(String startStr, String endStr) {
        try {
            LocalDateTime start = LocalDateTime.parse(startStr);
            LocalDateTime end = LocalDateTime.parse(endStr);

            // 1. La fine non può essere prima (o uguale) all'inizio
            if (end.isBefore(start) || end.isEqual(start)) {
                throw new IllegalArgumentException("Errore: l'orario di fine deve essere successivo all'orario di inizio.");
            }

            // 2. Controllo fascia lavorativa (08:00 - 20:00)
            LocalTime startTime = start.toLocalTime();
            LocalTime endTime = end.toLocalTime();
            LocalTime minTime = LocalTime.of(8, 0);
            LocalTime maxTime = LocalTime.of(20, 0);

            if (startTime.isBefore(minTime) || endTime.isAfter(maxTime)) {
                throw new IllegalArgumentException("Errore: le prenotazioni sono consentite solo nella fascia oraria 08:00 - 20:00.");
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Errore: Formato data/ora non valido. Usa il formato ISO-8601.");
        }
    }
=======

    // --- Metodi helper per permessi ---

    private void verificaAdmin(Jwt jwt) {
        if (!isAdmin(jwt)) {
            throw new AccessDeniedException("Solo gli admin possono eseguire questa operazione");
        }
    }

    private boolean isAdmin(Jwt jwt) {
        List<String> ruoli = jwt.getClaimAsStringList("roles");
        return ruoli != null && ruoli.contains("RoomBooking.Admin");
    }
>>>>>>> Stashed changes
}