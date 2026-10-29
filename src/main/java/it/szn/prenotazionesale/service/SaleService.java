package it.szn.prenotazionesale.service;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import it.szn.prenotazionesale.model.SalaDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servizio per il recupero dell'elenco delle sale riunioni da Microsoft Graph.
 * Mantiene una cache in memoria con durata di 24 ore per evitare chiamate HTTP remote ridondanti,
 * trattandosi di metadati fisici statici.
 */
@Slf4j
@Service
public class SaleService {

    private final GraphServiceClient graphClient;

    /** Cache in-memory delle sale recuperate da Graph. */
    private volatile List<SalaDTO> saleCache = null;

    /** Timestamp dell'ultimo recupero effettuato con successo. */
    private volatile long lastFetchTimestamp = 0L;

    /** Durata di validitÃ  della cache: 24 ore (in millisecondi). */
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L;

    public SaleService(GraphServiceClient graphClient) {
        this.graphClient = graphClient;
    }

    /**
     * Recupera l'elenco delle sale riunioni censite in Microsoft 365 Exchange Online.
     * Se la cache in memoria Ã¨ ancora valida (< 24 ore), restituisce immediatamente i dati
     * memorizzati senza effettuare chiamate di rete. In caso contrario (prima esecuzione o
     * cache scaduta), interroga Microsoft Graph e aggiorna la cache in modo thread-safe.
     *
     * @return lista non modificabile delle sale disponibili
     */
    public List<SalaDTO> getSale() {
        long now = System.currentTimeMillis();
        if (saleCache != null && (now - lastFetchTimestamp) < CACHE_TTL_MS) {
            return saleCache;
        }

        synchronized (this) {
            if (saleCache != null && (now - lastFetchTimestamp) < CACHE_TTL_MS) {
                return saleCache;
            }

            log.info("Recupero elenco sale da Microsoft Graph (cache assente o scaduta)");
            var sale = graphClient.places()
                    .graphRoom()
                    .get()
                    .getValue()
                    .stream()
                    .map(room -> SalaDTO.builder()
                            .id(room.getId())
                            .nome(room.getDisplayName())
                            .email(room.getEmailAddress())
                            .build())
                    .collect(Collectors.toList());

            log.info("Recuperate {} sale da Microsoft Graph", sale.size());
            this.saleCache = Collections.unmodifiableList(sale);
            this.lastFetchTimestamp = System.currentTimeMillis();
            return this.saleCache;
        }
    }

    /**
     * Invalida esplicitamente la cache delle sale, forzando la rilettura da Microsoft Graph
     * alla successiva invocazione di {@link #getSale()}.
     */
    public synchronized void svuotaCache() {
        this.saleCache = null;
        this.lastFetchTimestamp = 0L;
        log.info("Cache delle sale invalidata manualmente");
    }
}