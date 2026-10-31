package it.szn.prenotazionesale.graph;

import com.microsoft.graph.models.Event;
import com.microsoft.graph.models.EventCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.users.item.calendarview.CalendarViewRequestBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphCalendarGatewayTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private GraphServiceClient graphClient;

    private GraphCalendarGateway gateway;

    private static final String SALA_EMAIL = "sala-test@szn.it";
    private static final String START_ISO = "2026-11-01T08:00:00";
    private static final String END_ISO = "2026-11-01T20:00:00";

    @BeforeEach
    void setUp() {
        gateway = new GraphCalendarGateway(graphClient);
    }

    private Event creaEventoMock(String id, String subject) {
        Event event = new Event();
        event.setId(id);
        event.setSubject(subject);
        return event;
    }

    @Test
    @DisplayName("Paginazione multipla su 3 pagine: restituisce tutti gli eventi aggregati")
    void testGetEventi_PaginazioneMultiplaTrePagine_RestituisceTuttiGliEventi() {
        CalendarViewRequestBuilder mockCalendarView = graphClient.users().byUserId(SALA_EMAIL).calendarView();

        Event ev1 = creaEventoMock("ev-1", "Evento 1");
        Event ev2 = creaEventoMock("ev-2", "Evento 2");
        Event ev3 = creaEventoMock("ev-3", "Evento 3");
        Event ev4 = creaEventoMock("ev-4", "Evento 4");
        Event ev5 = creaEventoMock("ev-5", "Evento 5");

        EventCollectionResponse page1 = new EventCollectionResponse();
        page1.setValue(List.of(ev1, ev2));
        page1.setOdataNextLink("https://graph.microsoft.com/v1.0/users/sala/calendarView?$skip=2");

        EventCollectionResponse page2 = new EventCollectionResponse();
        page2.setValue(List.of(ev3, ev4));
        page2.setOdataNextLink("https://graph.microsoft.com/v1.0/users/sala/calendarView?$skip=4");

        EventCollectionResponse page3 = new EventCollectionResponse();
        page3.setValue(List.of(ev5));
        page3.setOdataNextLink(null);

        CalendarViewRequestBuilder mockPage2Builder = mock(CalendarViewRequestBuilder.class);
        CalendarViewRequestBuilder mockPage3Builder = mock(CalendarViewRequestBuilder.class);

        when(mockCalendarView.get(any())).thenReturn(page1);
        when(mockCalendarView.withUrl(page1.getOdataNextLink())).thenReturn(mockPage2Builder);
        when(mockPage2Builder.get(any())).thenReturn(page2);
        when(mockCalendarView.withUrl(page2.getOdataNextLink())).thenReturn(mockPage3Builder);
        when(mockPage3Builder.get(any())).thenReturn(page3);

        List<Event> risultati = gateway.getEventi(SALA_EMAIL, START_ISO, END_ISO);

        assertNotNull(risultati);
        assertEquals(5, risultati.size());
        assertEquals("ev-1", risultati.get(0).getId());
        assertEquals("ev-2", risultati.get(1).getId());
        assertEquals("ev-3", risultati.get(2).getId());
        assertEquals("ev-4", risultati.get(3).getId());
        assertEquals("ev-5", risultati.get(4).getId());

        verify(mockCalendarView).get(any());
        verify(mockPage2Builder).get(any());
        verify(mockPage3Builder).get(any());
    }

    @Test
    @DisplayName("Paginazione su pagina singola senza nextLink: restituisce gli eventi della pagina")
    void testGetEventi_PaginaSingola_RestituisceEventi() {
        CalendarViewRequestBuilder mockCalendarView = graphClient.users().byUserId(SALA_EMAIL).calendarView();

        Event ev1 = creaEventoMock("ev-10", "Riunione 1");
        Event ev2 = creaEventoMock("ev-20", "Riunione 2");

        EventCollectionResponse singlePage = new EventCollectionResponse();
        singlePage.setValue(List.of(ev1, ev2));
        singlePage.setOdataNextLink(null);

        when(mockCalendarView.get(any())).thenReturn(singlePage);

        List<Event> risultati = gateway.getEventi(SALA_EMAIL, START_ISO, END_ISO);

        assertEquals(2, risultati.size());
        assertEquals("ev-10", risultati.get(0).getId());
        assertEquals("ev-20", risultati.get(1).getId());
        verify(mockCalendarView, never()).withUrl(anyString());
    }

    @Test
    @DisplayName("Risultato vuoto: restituisce lista vuota senza errori")
    void testGetEventi_RisultatoVuoto_RestituisceListaVuota() {
        CalendarViewRequestBuilder mockCalendarView = graphClient.users().byUserId(SALA_EMAIL).calendarView();

        EventCollectionResponse emptyPage = new EventCollectionResponse();
        emptyPage.setValue(Collections.emptyList());
        emptyPage.setOdataNextLink(null);

        when(mockCalendarView.get(any())).thenReturn(emptyPage);

        List<Event> risultati = gateway.getEventi(SALA_EMAIL, START_ISO, END_ISO);

        assertNotNull(risultati);
        assertTrue(risultati.isEmpty());
    }

    @Test
    @DisplayName("Superamento limite massimo di 50 pagine: solleva IllegalStateException")
    void testGetEventi_SuperamentoLimiteMassimoPagine_LanciaEccezione() {
        CalendarViewRequestBuilder mockCalendarView = graphClient.users().byUserId(SALA_EMAIL).calendarView();

        EventCollectionResponse loopingPage = new EventCollectionResponse();
        loopingPage.setValue(List.of(creaEventoMock("ev-loop", "Loop")));
        loopingPage.setOdataNextLink("https://graph.microsoft.com/v1.0/loop-url");

        when(mockCalendarView.get(any())).thenReturn(loopingPage);
        when(mockCalendarView.withUrl(anyString())).thenReturn(mockCalendarView);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                gateway.getEventi(SALA_EMAIL, START_ISO, END_ISO)
        );

        assertTrue(ex.getMessage().contains("Superato il limite massimo di pagine (50)"));
    }

    @Test
    @DisplayName("Verifica deleghe operazioni CRUD ed eccezioni")
    void testOperazioniCrudDelega() {
        Event mockEvt = creaEventoMock("evt-crud", "Test CRUD");

        when(graphClient.users().byUserId(SALA_EMAIL).calendar().events().post(any(Event.class)))
                .thenReturn(mockEvt);
        Event creato = gateway.creaEvento(SALA_EMAIL, mockEvt);
        assertEquals("evt-crud", creato.getId());

        when(graphClient.users().byUserId(SALA_EMAIL).events().byEventId("evt-crud").get(any()))
                .thenReturn(mockEvt);
        Event letto = gateway.getEvento(SALA_EMAIL, "evt-crud", "id", "subject");
        assertEquals("evt-crud", letto.getId());

        when(graphClient.users().byUserId(SALA_EMAIL).events().byEventId("evt-crud").patch(any(Event.class)))
                .thenReturn(mockEvt);
        Event aggiornato = gateway.aggiornaEvento(SALA_EMAIL, "evt-crud", mockEvt);
        assertEquals("evt-crud", aggiornato.getId());

        assertDoesNotThrow(() -> gateway.eliminaEvento(SALA_EMAIL, "evt-crud"));
        verify(graphClient.users().byUserId(SALA_EMAIL).events().byEventId("evt-crud")).delete();

        EventCollectionResponse filterResponse = new EventCollectionResponse();
        filterResponse.setValue(List.of(mockEvt));
        when(graphClient.users().byUserId(SALA_EMAIL).events().get(any())).thenReturn(filterResponse);
        List<Event> filtrati = gateway.cercaEventiPerFiltro(SALA_EMAIL, "iCalUId eq 'test'");
        assertEquals(1, filtrati.size());
    }
}

