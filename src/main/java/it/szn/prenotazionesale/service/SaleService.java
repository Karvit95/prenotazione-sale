package it.szn.prenotazionesale.service;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.model.SalaDTO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@AllArgsConstructor
@Service
public class SaleService {

    private final GraphServiceClient graphClient;

    public List<SalaDTO> getSale() {
        log.debug("Recupero elenco sale da Microsoft Graph");
        var sale = graphClient.places()
                .graphRoom()
                .get()
                .getValue()
                .stream()
                .map(room -> SalaDTO.builder()
                        .id(room.getId())
                        .nome(room.getDisplayName())
                        .email(room.getEmailAddress())
                        .build())
                .collect(Collectors.toList());
        log.info("Recuperate {} sale", sale.size());
        return sale;
    }
}