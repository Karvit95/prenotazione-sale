package it.szn.prenotazionesale.service;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.model.SalaDTO;
import lombok.AllArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
public class SaleService {

    private final GraphServiceClient graphClient;

    public List<SalaDTO> getSale() {
        return graphClient.places()
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
    }
}