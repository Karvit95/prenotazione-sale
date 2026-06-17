package it.szn.prenotazionesale.service;

import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.model.SalaDTO;
import lombok.AllArgsConstructor;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

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

        return tutteLeSale.parallelStream()
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
        var event = buildEvent(request);

        var createdEvent = graphClient.users()
                .byUserId(request.getSalaEmail())
                .calendar()
                .events()
                .post(event);

        return mapToDTO(createdEvent, request.getSalaEmail(), jwt);
    }

    public PrenotazioneDTO modificaPrenotazione(String eventId, PrenotazioneRequestDTO request, Jwt jwt) {
        var event = buildEvent(request);

        var updatedEvent = graphClient.users()
                .byUserId(request.getSalaEmail())
                .events()
                .byEventId(eventId)
                .patch(event);

        return mapToDTO(updatedEvent, request.getSalaEmail(), jwt);
    }

    public void cancellaPrenotazione(String eventId, String salaEmail, Jwt jwt) {
        var event = graphClient.users()
                .byUserId(salaEmail)
                .events()
                .byEventId(eventId)
                .get();

        List<String> ruoli = jwt.getClaimAsStringList("roles");
        boolean isAdmin = ruoli != null && ruoli.contains("RoomBooking.Admin");
        String organizzatoreNome = event.getOrganizer() != null
                && event.getOrganizer().getEmailAddress() != null
                ? event.getOrganizer().getEmailAddress().getName()
                : null;
        boolean isOrganizzatore = organizzatoreNome != null
                && organizzatoreNome.equals(jwt.getSubject());

        if (!isAdmin && !isOrganizzatore) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Non hai i permessi per cancellare questa prenotazione"
            );
        }

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
        String organizzatoreId = event.getOrganizer() != null
                && event.getOrganizer().getEmailAddress() != null
                ? event.getOrganizer().getEmailAddress().getName()
                : null;

        String utenteCorrenteId = jwt.getSubject();
        List<String> ruoli = jwt.getClaimAsStringList("roles");
        boolean isAdmin = ruoli != null && ruoli.contains("RoomBooking.Admin");
        boolean modificabile = isAdmin || 
                (organizzatoreId != null && organizzatoreId.equals(utenteCorrenteId));

        return PrenotazioneDTO.builder()
                .id(event.getId())
                .salaEmail(salaEmail)
                .titolo(event.getSubject())
                .descrizione(event.getBody() != null ? event.getBody().getContent() : null)
                .start(event.getStart() != null ? event.getStart().getDateTime() : null)
                .end(event.getEnd() != null ? event.getEnd().getDateTime() : null)
                .organizzatoreNome(event.getOrganizer() != null
                        && event.getOrganizer().getEmailAddress() != null
                        ? event.getOrganizer().getEmailAddress().getName()
                        : null)
                .modificabile(modificabile)
                .build();
    }
}