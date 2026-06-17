package it.szn.prenotazionesale.controller;

import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.service.PrenotazioniService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/prenotazioni")
public class PrenotazioniController {

    private final PrenotazioniService prenotazioniService;

    public PrenotazioniController(PrenotazioniService prenotazioniService) {
        this.prenotazioniService = prenotazioniService;
    }

    // GET /api/prenotazioni?salaEmail=...&dataInizio=...&dataFine=...
    @GetMapping
    public ResponseEntity<List<PrenotazioneDTO>> getPrenotazioni(
            @RequestParam String salaEmail,
            @RequestParam String dataInizio,
            @RequestParam String dataFine,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(
                prenotazioniService.getPrenotazioni(salaEmail, dataInizio, dataFine, jwt)
        );
    }
    
    // GET /api/prenotazioni/tutte?dataInizio=...&dataFine=...
    @GetMapping("/tutte")
    public ResponseEntity<List<PrenotazioneDTO>> getPrenotazioniTutteSale(
            @RequestParam String dataInizio,
            @RequestParam String dataFine,
            @AuthenticationPrincipal Jwt jwt) {

        List<PrenotazioneDTO> prenotazioni = prenotazioniService.getPrenotazioniTutteSale(dataInizio, dataFine, jwt);
        return ResponseEntity.ok(prenotazioni);
    }

    // POST /api/prenotazioni
    @PostMapping
    public ResponseEntity<PrenotazioneDTO> creaPrenotazione(
            @RequestBody PrenotazioneRequestDTO request,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(
                prenotazioniService.creaPrenotazione(request, jwt)
        );
    }

    // PATCH /api/prenotazioni/{eventId}?salaEmail=...
    @PatchMapping("/{eventId}")
    public ResponseEntity<PrenotazioneDTO> modificaPrenotazione(
            @PathVariable String eventId,
            @RequestBody PrenotazioneRequestDTO request,
            @AuthenticationPrincipal Jwt jwt) {

        // Verifica che l'utente possa modificare - il service lo controlla
        return ResponseEntity.ok(
                prenotazioniService.modificaPrenotazione(eventId, request, jwt)
        );
    }

    // DELETE /api/prenotazioni/{eventId}?salaEmail=...
    @DeleteMapping("/{eventId}")
    public ResponseEntity<Void> cancellaPrenotazione(
            @PathVariable String eventId,
            @RequestParam String salaEmail,
            @AuthenticationPrincipal Jwt jwt) {

        // Verifica permessi prima di cancellare
        prenotazioniService.cancellaPrenotazione(eventId, salaEmail, jwt);
        return ResponseEntity.noContent().build();
    }
}