package it.szn.prenotazionesale.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrenotazioneRequestDTO {

    @NotBlank(message = "La sala è obbligatoria")
    private String salaEmail;

    @NotBlank(message = "Il titolo è obbligatorio")
    private String titolo;

    private String descrizione;

    @NotBlank(message = "La data/ora inizio è obbligatoria")
    private String start;

    @NotBlank(message = "La data/ora fine è obbligatoria")
    private String end;

    private String salaEmailOriginale;

    // --- Campi per ricorrenza ---

    /** "daily" | "weekly" | "monthly" — null se evento singolo */
    private String pattern;

    /** Ogni N giorni/settimane/mesi (default: 1) */
    private Integer intervallo;

    /** Solo per weekly: ["Monday","Wednesday",...] */
    private List<String> giorniSettimana;

    /** Data fine ricorrenza in formato ISO (obbligatoria se pattern != null) */
    private String dataFine;

    /** "SINGOLA" | "SERIE" — per PATCH/DELETE su eventi ricorrenti */
    private String tipoModifica;

    /**
     * ID del series master su Graph, presente solo se l'evento che si sta
     * modificando/cancellando è un'occorrenza di una serie ricorrente.
     * Necessario per operare correttamente su "tutta la serie"
     */
    private String seriesMasterId;
}