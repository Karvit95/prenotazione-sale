package it.szn.prenotazionesale.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Risposta strutturata per l'endpoint GET /api/prenotazioni/tutte che supporta
 * risultati parziali qualora alcune sale non siano raggiungibili o vadano in timeout.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrenotazioniTutteSaleResponse {

    @Builder.Default
    private List<PrenotazioneDTO> prenotazioni = new ArrayList<>();

    @Builder.Default
    private List<SalaNonDisponibileDTO> saleNonDisponibili = new ArrayList<>();

    private boolean parziale;

    @JsonIgnore
    private int totaleSale;
}

