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

	private String salaEmail; 
	private String titolo; 
	private String descrizione;
	private String start;
	private String end; 
	private String salaEmailOriginale;

}
