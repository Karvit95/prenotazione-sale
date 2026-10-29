package it.szn.prenotazionesale.service;

import com.microsoft.graph.models.Room;
import com.microsoft.graph.models.RoomCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.model.SalaDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SaleServiceTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private GraphServiceClient graphClient;

    @InjectMocks
    private SaleService saleService;

    @Test
    @DisplayName("Dovrebbe recuperare correttamente la lista di sale mappate in SalaDTO")
    void getSale_Success() {
        // Arrange
        Room room1 = new Room();
        room1.setId("room-id-1");
        room1.setDisplayName("Sala Pietro Dohrn");
        room1.setEmailAddress("dohrn@szn.it");

        Room room2 = new Room();
        room2.setId("room-id-2");
        room2.setDisplayName("Sala Polifunzionale");
        room2.setEmailAddress("polifunzionale@szn.it");

        RoomCollectionResponse response = new RoomCollectionResponse();
        response.setValue(List.of(room1, room2));

        when(graphClient.places().graphRoom().get()).thenReturn(response);

        // Act
        List<SalaDTO> result = saleService.getSale();

        // Assert
        assertThat(result).isNotNull().hasSize(2);

        SalaDTO dto1 = result.get(0);
        assertThat(dto1.getId()).isEqualTo("room-id-1");
        assertThat(dto1.getNome()).isEqualTo("Sala Pietro Dohrn");
        assertThat(dto1.getEmail()).isEqualTo("dohrn@szn.it");

        SalaDTO dto2 = result.get(1);
        assertThat(dto2.getId()).isEqualTo("room-id-2");
        assertThat(dto2.getNome()).isEqualTo("Sala Polifunzionale");
        assertThat(dto2.getEmail()).isEqualTo("polifunzionale@szn.it");

        verify(graphClient.places().graphRoom(), times(1)).get();
    }

    @Test
    @DisplayName("Dovrebbe utilizzare la cache in memoria alla seconda chiamata senza re-interrogare Graph")
    void getSale_CacheUsedWithinTtl() {
        // Arrange
        Room room = new Room();
        room.setId("room-id-1");
        room.setDisplayName("Sala Pietro Dohrn");
        room.setEmailAddress("dohrn@szn.it");

        RoomCollectionResponse response = new RoomCollectionResponse();
        response.setValue(List.of(room));

        when(graphClient.places().graphRoom().get()).thenReturn(response);

        // Act
        List<SalaDTO> primaChiamata = saleService.getSale();
        List<SalaDTO> secondaChiamata = saleService.getSale();

        // Assert
        assertThat(primaChiamata).isNotNull().hasSize(1);
        assertThat(secondaChiamata).isNotNull().hasSize(1);
        // Graph deve essere stato chiamato 1 sola volta
        verify(graphClient.places().graphRoom(), times(1)).get();
    }

    @Test
    @DisplayName("Dovrebbe restituire una lista vuota se Microsoft Graph non restituisce alcuna sala")
    void getSale_EmptyList() {
        // Arrange
        RoomCollectionResponse response = new RoomCollectionResponse();
        response.setValue(List.of());

        when(graphClient.places().graphRoom().get()).thenReturn(response);

        // Act
        List<SalaDTO> result = saleService.getSale();

        // Assert
        assertThat(result).isNotNull().isEmpty();
        verify(graphClient.places().graphRoom(), times(1)).get();
    }

    @Test
    @DisplayName("Dovrebbe propagare l'eccezione se la chiamata a Microsoft Graph fallisce")
    void getSale_GraphException_PropagatesException() {
        // Arrange
        when(graphClient.places().graphRoom().get())
                .thenThrow(new RuntimeException("Errore di connessione a Microsoft Graph"));

        // Act & Assert
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> saleService.getSale()
        );

        verify(graphClient.places().graphRoom(), times(1)).get();
    }
}