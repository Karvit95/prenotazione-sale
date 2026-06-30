package it.szn.prenotazionesale.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}