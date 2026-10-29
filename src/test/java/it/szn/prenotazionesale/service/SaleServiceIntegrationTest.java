package it.szn.prenotazionesale.service;

import com.microsoft.graph.models.Room;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.model.SalaDTO;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
@Disabled("Test di integrazione manuale che richiede connessione remota con secret Azure valido")
class SaleServiceIntegrationTest {

    @Autowired
    private SaleService saleService;

    @Autowired
    private GraphServiceClient graphClient;

    @Test
    @DisplayName("Recupera e stampa a video le sale reali da Microsoft Graph")
    void stampaSaleReali() {
        System.out.println("=================================================");
        System.out.println(">> CHIAMATA A MICROSOFT GRAPH PER RECUPERO SALE <<");
        System.out.println("=================================================");

        try {
            // 1. Dati mappati tramite SaleService
            List<SalaDTO> sale = saleService.getSale();

            System.out.println("\n--- [SaleService.getSale()] Numero sale trovate: " + sale.size() + " ---");
            for (int i = 0; i < sale.size(); i++) {
                SalaDTO s = sale.get(i);
                System.out.printf("[%d] ID: %s | NOME: %s | EMAIL: %s%n",
                        i + 1, s.getId(), s.getNome(), s.getEmail());
            }

            // 2. Dati grezzi dei Room da Graph per ispezionare tutti i campi disponibili
            var rawRooms = graphClient.places().graphRoom().get().getValue();
            System.out.println("\n--- [Dettagli completi oggetti Room da Graph] ---");
            for (Room r : rawRooms) {
                System.out.println("---------------------------------------------");
                System.out.println("DisplayName       : " + r.getDisplayName());
                System.out.println("EmailAddress      : " + r.getEmailAddress());
                System.out.println("Id                : " + r.getId());
                System.out.println("Building          : " + r.getBuilding());
                System.out.println("FloorNumber       : " + r.getFloorNumber());
                System.out.println("Capacity          : " + r.getCapacity());
                System.out.println("Phone             : " + r.getPhone());
                System.out.println("AdditionalData    : " + r.getAdditionalData());
            }
            System.out.println("=================================================");

        } catch (Exception e) {
            System.err.println("ERRORE DURANTE LA CHIAMATA A MICROSOFT GRAPH: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
}
