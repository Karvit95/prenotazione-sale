package it.szn.prenotazionesale.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrenotazioneDTO {
	
	private String id;              // ID evento Exchange (serve per modifica/cancellazione)
    private String salaId;          // ID della room mailbox (= resourceId per FullCalendar)
    private String salaEmail;       // Email della room (serve per creare l'evento)
    private String titolo;          // Oggetto della riunione
    private String descrizione;     // Corpo dell'evento (opzionale)
    private String start;           // ISO 8601: "2026-06-03T10:30:00"
    private String end;             // ISO 8601: "2026-06-03T12:00:00"
    private String organizzatoreId; // OID Entra ID di chi ha creato (per controllo permessi)
    private String organizzatoreNome; // Display name dell'organizzatore
    private boolean modificabile;   // true se l'utente loggato può modificare

    // --- Campi per ricorrenza ---

    /** ID della serie master (null se evento singolo) */
    private String seriesMasterId;

    /** true se l'evento fa parte di una serie ricorrente */
    private boolean ricorrente;

    /** "daily" | "weekly" | "monthly" | "yearly" — per visualizzazione */
    private String pattern;
}