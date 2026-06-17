package it.szn.prenotazionesale.controller;

import it.szn.prenotazionesale.model.SalaDTO;
import it.szn.prenotazionesale.service.SaleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sale")
public class SaleController {

    private final SaleService saleService;

    public SaleController(SaleService saleService) {
        this.saleService = saleService;
    }

    @GetMapping
    public ResponseEntity<List<SalaDTO>> getSale() {
        return ResponseEntity.ok(saleService.getSale());
    }
}