package it.szn.prenotazionesale.graph;

import com.microsoft.graph.models.Event;
import com.microsoft.graph.models.EventCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.exception.GraphPaginationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Gateway perimetrale verso Microsoft Graph API per la gestione degli eventi del calendario (Exchange Online).
 * Incapsula tutte le chiamate REST Kiota SDK gestendo la paginazione trasparente (top, nextLink)
 * e la propagazione diretta delle eccezioni remote (senza alterare gli status code).
 */
@Slf4j
@Component
public class GraphCalendarGateway {

    private static final int MAX_PAGINE = 50;
    private static final int TOP_PER_PAGINA = 999;
    private static final String TIMEZONE_ROMA = "outlook.timezone=\"Europe/Rome\"";

    private final GraphServiceClient graphClient;

    public GraphCalendarGateway(GraphServiceClient graphClient) {
        this.graphClient = graphClient;
    }

    /**
     * Recupera tutti gli eventi presenti nel calendario della sala nell'intervallo temporale indicato.
     * Applica l'header Prefer con fuso Europe/Rome, imposta top(999) e segue i link di paginazione (@odata.nextLink)
     * fino a esaurimento, garantendo la restituzione di tutti gli eventi con un limite di sicurezza di 50 pagine.
     *
     * @param salaEmail  casella di posta della sala
     * @param dataInizio estremo inferiore intervallo (formato ISO-8601)
     * @param dataFine   estremo superiore intervallo (formato ISO-8601)
     * @return lista completa degli eventi dell'intervallo
     * @throws GraphPaginationException se il numero di pagine supera il limite massimo di sicurezza (50)
     */
    public List<Event> getEventi(String salaEmail, String dataInizio, String dataFine) {
        List<Event> tuttiEventi = new ArrayList<>();
        var requestBuilder = graphClient.users().byUserId(salaEmail).calendarView();

        EventCollectionResponse pagina = requestBuilder.get(req -> {
            req.queryParameters.startDateTime = dataInizio;
            req.queryParameters.endDateTime = dataFine;
            req.queryParameters.top = TOP_PER_PAGINA;
            req.headers.add("Prefer", TIMEZONE_ROMA);
        });

        int numeroPagine = 0;
        while (pagina != null) {
            numeroPagine++;
            if (numeroPagine > MAX_PAGINE) {
                log.warn("Superato il limite massimo di pagine ({}) per la sala {} nell'intervallo {} - {}",
                        MAX_PAGINE, salaEmail, dataInizio, dataFine);
                throw new GraphPaginationException(String.format(
                        "Superato il limite massimo di pagine (%d) per la lettura del calendario della sala %s",
                        MAX_PAGINE, salaEmail));
            }

            List<Event> eventiPagina = pagina.getValue();
            if (eventiPagina != null && !eventiPagina.isEmpty()) {
                tuttiEventi.addAll(eventiPagina);
            }

            String nextLink = pagina.getOdataNextLink();
            if (nextLink == null || nextLink.isBlank()) {
                break;
            }

            pagina = requestBuilder.withUrl(nextLink).get(req -> {
                req.headers.add("Prefer", TIMEZONE_ROMA);
            });
        }

        return tuttiEventi;
    }

    /**
     * Recupera un singolo evento per ID dal calendario della sala, con fuso orario Europe/Rome.
     *
     * @param salaEmail casella di posta della sala
     * @param eventId   identificativo dell'evento o del master della serie
     * @return evento recuperato
     */
    public Event getEvento(String salaEmail, String eventId) {
        return getEvento(salaEmail, eventId, (String[]) null);
    }

    /**
     * Recupera un singolo evento per ID dal calendario della sala, con fuso orario Europe/Rome
     * ed eventuali campi di proiezione select.
     *
     * @param salaEmail   casella di posta della sala
     * @param eventId     identificativo dell'evento o del master della serie
     * @param campiSelect campi opzionali da richiedere in proiezione OData $select
     * @return evento recuperato
     */
    public Event getEvento(String salaEmail, String eventId, String... campiSelect) {
        return graphClient.users().byUserId(salaEmail).events().byEventId(eventId).get(req -> {
            req.headers.add("Prefer", TIMEZONE_ROMA);
            if (campiSelect != null && campiSelect.length > 0) {
                req.queryParameters.select = campiSelect;
            }
        });
    }

    /**
     * Crea un nuovo evento (singolo o serie ricorrente) sul calendario della sala.
     *
     * @param salaEmail casella di posta della sala
     * @param evento    oggetto Event con dettagli, orari e ricorrenza
     * @return evento creato restituito da Microsoft Graph
     */
    public Event creaEvento(String salaEmail, Event evento) {
        return graphClient.users().byUserId(salaEmail).calendar().events().post(evento);
    }

    /**
     * Aggiorna un evento esistente (singola occorrenza o serie) sul calendario della sala.
     *
     * @param salaEmail casella di posta della sala
     * @param eventId   identificativo dell'evento da aggiornare
     * @param evento    oggetto Event contenente le proprietà modificate
     * @return evento aggiornato restituito da Microsoft Graph
     */
    public Event aggiornaEvento(String salaEmail, String eventId, Event evento) {
        return graphClient.users().byUserId(salaEmail).events().byEventId(eventId).patch(evento);
    }

    /**
     * Elimina un evento dal calendario della sala o dell'organizzatore.
     *
     * @param utenteOSalaEmail casella di posta dell'utente o della sala
     * @param eventId          identificativo dell'evento da eliminare
     */
    public void eliminaEvento(String utenteOSalaEmail, String eventId) {
        graphClient.users().byUserId(utenteOSalaEmail).events().byEventId(eventId).delete();
    }

    /**
     * Cerca eventi sul calendario di un utente o sala tramite filtro OData (es. iCalUId eq '...').
     *
     * @param utenteEmail casella di posta dell'utente/organizzatore
     * @param filtroOData espressione di filtro OData conforme allo standard Graph
     * @return lista degli eventi corrispondenti al filtro
     */
    public List<Event> cercaEventiPerFiltro(String utenteEmail, String filtroOData) {
        var eventsPage = graphClient.users().byUserId(utenteEmail).events().get(req -> {
            req.queryParameters.filter = filtroOData;
        });
        return (eventsPage != null && eventsPage.getValue() != null) ? eventsPage.getValue() : List.of();
    }
}
