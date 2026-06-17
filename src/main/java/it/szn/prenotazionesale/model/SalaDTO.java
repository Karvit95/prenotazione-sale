package it.szn.prenotazionesale.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalaDTO {
	
	private String id;           // ID univoco Graph
    private String nome;         // Display name (es. "Sala Pietro Dohrn")
    private String email;        // Email della room mailbox

}
