package it.szn.prenotazionesale.controller;

import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.service.PrenotazioniService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
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
        log.info("GET /api/prenotazioni - sala: {}, dal: {}, al: {}", salaEmail, dataInizio, dataFine);
        var result = prenotazioniService.getPrenotazioni(salaEmail, dataInizio, dataFine, jwt);
        log.debug("GET /api/prenotazioni - restituite {} prenotazioni", result.size());
        return ResponseEntity.ok(result);
    }
    
    // GET /api/prenotazioni/tutte?dataInizio=...&dataFine=...
    @GetMapping("/tutte")
    public ResponseEntity<List<PrenotazioneDTO>> getPrenotazioniTutteSale(
            @RequestParam String dataInizio,
            @RequestParam String dataFine,
            @AuthenticationPrincipal Jwt jwt) {
        log.info("GET /api/prenotazioni/tutte - dal: {}, al: {}", dataInizio, dataFine);
        List<PrenotazioneDTO> prenotazioni = prenotazioniService.getPrenotazioniTutteSale(dataInizio, dataFine, jwt);
        log.debug("GET /api/prenotazioni/tutte - restituite {} prenotazioni", prenotazioni.size());
        return ResponseEntity.ok(prenotazioni);
    }

    // POST /api/prenotazioni
    @PostMapping
    public ResponseEntity<PrenotazioneDTO> creaPrenotazione(
            @Valid @RequestBody PrenotazioneRequestDTO request,
            @AuthenticationPrincipal Jwt jwt) {
        log.info("POST /api/prenotazioni - sala: {}, titolo: {}", request.getSalaEmail(), request.getTitolo());
        var result = prenotazioniService.creaPrenotazione(request, jwt);
        log.info("POST /api/prenotazioni - creata con ID {}", result.getId());
        return ResponseEntity.ok(result);
    }

    // PATCH /api/prenotazioni/{eventId}?salaEmail=...
    @PatchMapping("/{eventId}")
    public ResponseEntity<PrenotazioneDTO> modificaPrenotazione(
            @PathVariable String eventId,
            @Valid @RequestBody PrenotazioneRequestDTO request,
            @AuthenticationPrincipal Jwt jwt) {
        log.info("PATCH /api/prenotazioni/{} - sala: {}", eventId, request.getSalaEmail());
        var result = prenotazioniService.modificaPrenotazione(eventId, request, jwt);
        log.info("PATCH /api/prenotazioni/{} - modificata con successo", eventId);
        return ResponseEntity.ok(result);
    }

    // DELETE /api/prenotazioni/{eventId}?salaEmail=...&tipoCancellazione=SERIE
    @DeleteMapping("/{eventId}")
    public ResponseEntity<Void> cancellaPrenotazione(
            @PathVariable String eventId,
            @RequestParam String salaEmail,
            @RequestParam(defaultValue = "SERIE") String tipoCancellazione,
            @AuthenticationPrincipal Jwt jwt) {
        log.info("DELETE /api/prenotazioni/{} - sala: {}, tipo: {}", eventId, salaEmail, tipoCancellazione);
        prenotazioniService.cancellaPrenotazione(eventId, salaEmail, tipoCancellazione, jwt);
        log.info("DELETE /api/prenotazioni/{} - cancellata con successo", eventId);
        return ResponseEntity.noContent().build();
    }
}