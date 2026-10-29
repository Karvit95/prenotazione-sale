package it.szn.prenotazionesale.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Configurazione dei thread pool asincroni per l'applicazione.
 * Definisce un executor con coda limitata e gestione della contropressione (backpressure)
 * per le chiamate parallele verso Microsoft Graph, prevenendo l'esaurimento della memoria heap.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Executor dedicato alle interrogazioni concorrenti di calendario su Microsoft Graph.
     * 
     * Caratteristiche:
     * - Core pool: 10 thread sempre disponibili.
     * - Max pool: fino a 20 thread in caso di picco di carico.
     * - Coda limitata (QueueCapacity: 40): impedisce l'accodamento illimitato di task prevenendo OutOfMemoryError.
     * - CallerRunsPolicy: se la coda Ã¨ satura, il task viene eseguito dal thread chiamante,
     *   rallentando l'ingestione e stabilizzando il backend.
     * - Graceful shutdown: attende fino a 30 secondi il completamento dei task in corso prima della chiusura.
     */
    @Bean(name = "graphQueryExecutor")
    public ThreadPoolTaskExecutor graphQueryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(40);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("graph-room-query-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}