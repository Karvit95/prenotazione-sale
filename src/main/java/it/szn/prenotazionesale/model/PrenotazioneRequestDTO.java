package it.szn.prenotazionesale.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrenotazioneRequestDTO {

	private String salaEmail; // Email della room da prenotare
	private String titolo; // Oggetto della riunione
	private String descrizione; // Opzionale
	private String start; // ISO 8601
	private String end; // ISO 8601

}
