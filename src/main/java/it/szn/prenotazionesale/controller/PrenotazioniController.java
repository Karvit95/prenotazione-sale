package it.szn.prenotazionesale.controller;

import it.szn.prenotazionesale.audit.SecurityAuditService;
import it.szn.prenotazionesale.model.PrenotazioneDTO;
import it.szn.prenotazionesale.model.PrenotazioneRequestDTO;
import it.szn.prenotazionesale.service.PrenotazioniService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/prenotazioni")
public class PrenotazioniController {

    private final PrenotazioniService prenotazioniService;
    private final SecurityAuditService auditService;
    private final HttpServletRequest httpServletRequest;

    public PrenotazioniController(PrenotazioniService prenotazioniService,
                                  SecurityAuditService auditService,
                                  HttpServletRequest httpServletRequest) {
        this.prenotazioniService = prenotazioniService;
        this.auditService = auditService;
        this.httpServletRequest = httpServletRequest;
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
    @PreAuthorize("hasRole('RoomBooking.Admin')")
    @PostMapping
    public ResponseEntity<PrenotazioneDTO> creaPrenotazione(
            @Valid @RequestBody PrenotazioneRequestDTO request,
            @AuthenticationPrincipal Jwt jwt) {
        log.info("POST /api/prenotazioni - sala: {}, intervallo: {} -> {}",
                request.getSalaEmail(), request.getStart(), request.getEnd());
        var result = prenotazioniService.creaPrenotazione(request, jwt);
        log.info("POST /api/prenotazioni - creata con ID {}", result.getId());

        auditService.logSuccess("CREATE_BOOKING", request.getSalaEmail(), jwt, httpServletRequest, Map.of(
                "bookingId", result.getId() != null ? result.getId() : "",
                "start", request.getStart() != null ? request.getStart() : "",
                "end", request.getEnd() != null ? request.getEnd() : "",
                "ricorrente", request.getPattern() != null
        ));

        return ResponseEntity.ok(result);
    }

    // PATCH /api/prenotazioni/{eventId}?salaEmail=...
    @PreAuthorize("hasRole('RoomBooking.Admin')")
    @PatchMapping("/{eventId}")
    public ResponseEntity<PrenotazioneDTO> modificaPrenotazione(
            @PathVariable String eventId,
            @Valid @RequestBody PrenotazioneRequestDTO request,
            @AuthenticationPrincipal Jwt jwt) {
        log.info("PATCH /api/prenotazioni/{} - sala: {}", eventId, request.getSalaEmail());
        var result = prenotazioniService.modificaPrenotazione(eventId, request, jwt);
        log.info("PATCH /api/prenotazioni/{} - modificata con successo", eventId);

        auditService.logSuccess("UPDATE_BOOKING", eventId, jwt, httpServletRequest, Map.of(
                "salaEmail", request.getSalaEmail() != null ? request.getSalaEmail() : "",
                "tipoModifica", request.getTipoModifica() != null ? request.getTipoModifica() : "SINGOLA"
        ));

        return ResponseEntity.ok(result);
    }

    // DELETE /api/prenotazioni/{eventId}?salaEmail=...&tipoCancellazione=SINGOLA&seriesMasterId=... (default: SINGOLA)
    @PreAuthorize("hasRole('RoomBooking.Admin')")
    @DeleteMapping("/{eventId}")
    public ResponseEntity<Void> cancellaPrenotazione(
            @PathVariable String eventId,
            @RequestParam String salaEmail,
            @RequestParam(defaultValue = "SINGOLA") String tipoCancellazione,
            @RequestParam(required = false) String seriesMasterId,
            @AuthenticationPrincipal Jwt jwt) {
        if (tipoCancellazione == null || (!tipoCancellazione.equalsIgnoreCase("SERIE") && !tipoCancellazione.equalsIgnoreCase("SINGOLA"))) {
            throw new IllegalArgumentException("Il tipo cancellazione deve essere 'SERIE' o 'SINGOLA'");
        }
        String tipoNorm = tipoCancellazione.toUpperCase();
        log.info("DELETE /api/prenotazioni/{} - sala: {}, tipo: {}, seriesMasterId: {}",
                eventId, salaEmail, tipoNorm, seriesMasterId);
        prenotazioniService.cancellaPrenotazione(eventId, salaEmail, tipoNorm, seriesMasterId, jwt);
        log.info("DELETE /api/prenotazioni/{} - cancellata con successo", eventId);

        auditService.logSuccess("DELETE_BOOKING", eventId, jwt, httpServletRequest, Map.of(
                "salaEmail", salaEmail,
                "tipoCancellazione", tipoNorm,
                "seriesMasterId", seriesMasterId != null ? seriesMasterId : ""
        ));

        return ResponseEntity.noContent().build();
    }
}
