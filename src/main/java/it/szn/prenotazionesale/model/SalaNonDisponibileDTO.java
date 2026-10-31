package it.szn.prenotazionesale.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Rappresenta una sala non disponibile durante l'interrogazione aggregata
 * delle prenotazioni (GET /api/prenotazioni/tutte).
 * <p>
 * Il campo motivo contiene un codice generico (TIMEOUT, ERRORE_GRAPH, ERRORE_INTERNO)
 * per evitare information disclosure verso il client.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalaNonDisponibileDTO {

    private String salaEmail;
    private String salaNome;
    private String motivo; // TIMEOUT, ERRORE_GRAPH, ERRORE_INTERNO
}

