package it.szn.prenotazionesale.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
    @Email(message = "L'indirizzo email della sala non è valido")
    @Size(max = 255, message = "L'email della sala non può superare 255 caratteri")
    private String salaEmail;

    @NotBlank(message = "Il titolo è obbligatorio")
    @Size(min = 1, max = 255, message = "Il titolo deve essere compreso tra 1 e 255 caratteri")
    private String titolo;

    @Size(max = 4000, message = "La descrizione non può superare 4000 caratteri")
    private String descrizione;

    @NotBlank(message = "La data/ora inizio è obbligatoria")
    @Size(max = 64, message = "Il formato data/ora inizio non è valido")
    private String start;

    @NotBlank(message = "La data/ora fine è obbligatoria")
    @Size(max = 64, message = "Il formato data/ora fine non è valido")
    private String end;

    @Size(max = 255, message = "L'email della sala originale non può superare 255 caratteri")
    private String salaEmailOriginale;

    // --- Campi per ricorrenza ---

    /** "daily" | "weekly" | "monthly" — null se evento singolo */
    @Pattern(regexp = "^(?i)(daily|weekly|monthly)$", message = "Il pattern di ricorrenza deve essere 'daily', 'weekly' o 'monthly'")
    private String pattern;

    /** Ogni N giorni/settimane/mesi (default: 1, max: 52) */
    @Min(value = 1, message = "L'intervallo deve essere di almeno 1")
    @Max(value = 52, message = "L'intervallo non può superare 52")
    private Integer intervallo;

    /** Solo per weekly: ["Monday","Wednesday",...] */
    @Size(max = 7, message = "Non è possibile selezionare più di 7 giorni della settimana")
    private List<@NotBlank @Size(max = 16) String> giorniSettimana;

    /** Data fine ricorrenza in formato ISO (obbligatoria se pattern != null) */
    @Size(max = 64, message = "Il formato data fine ricorrenza non è valido")
    private String dataFine;

    /** "SINGOLA" | "SERIE" — per PATCH/DELETE su eventi ricorrenti */
    @Pattern(regexp = "^(?i)(SERIE|SINGOLA)$", message = "Il tipo modifica deve essere 'SERIE' o 'SINGOLA'")
    private String tipoModifica;

    /**
     * ID del series master su Graph, presente solo se l'evento che si sta
     * modificando/cancellando è un'occorrenza di una serie ricorrente.
     */
    @Size(max = 255, message = "L'ID della serie non può superare 255 caratteri")
    private String seriesMasterId;
}
