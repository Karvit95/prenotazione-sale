package it.szn.prenotazionesale.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Filtro di Rate Limiting perimetrale su finestra temporale di 60 secondi per client IP:
 * - Max 120 richieste/minuto per operazioni di lettura (GET)
 * - Max 30 richieste/minuto per operazioni di scrittura (POST, PATCH, DELETE)
 * Include protezione anti-OOM con pulizia automatica delle voci scadute.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitingFilter implements Filter {

    private static final int MAX_READ_REQUESTS_PER_MINUTE = 120;
    private static final int MAX_WRITE_REQUESTS_PER_MINUTE = 30;
    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final ConcurrentHashMap<String, RequestCounter> requestCounts = new ConcurrentHashMap<>();

    private static class RequestCounter {
        private final long windowStart;
        private final AtomicInteger count;

        RequestCounter(long now) {
            this.windowStart = now;
            this.count = new AtomicInteger(0);
        }

        int incrementAndGet() {
            return count.incrementAndGet();
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String method = httpRequest.getMethod();

        if ("OPTIONS".equalsIgnoreCase(method)) {
            chain.doFilter(request, response);
            return;
        }

        long now = System.currentTimeMillis();
        if (requestCounts.size() > MAX_TRACKED_CLIENTS) {
            requestCounts.entrySet().removeIf(entry -> (now - entry.getValue().windowStart) > WINDOW_MS);
        }

        String clientIp = extractClientIp(httpRequest);
        boolean isWrite = "POST".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method);
        String bucketKey = clientIp + (isWrite ? ":WRITE" : ":READ");
        int maxAllowed = isWrite ? MAX_WRITE_REQUESTS_PER_MINUTE : MAX_READ_REQUESTS_PER_MINUTE;

        RequestCounter counter = requestCounts.compute(bucketKey, (key, existing) -> {
            if (existing == null || (now - existing.windowStart > WINDOW_MS)) {
                return new RequestCounter(now);
            }
            return existing;
        });

        int currentCount = counter.incrementAndGet();
        if (currentCount > maxAllowed) {
            log.warn("Rate limit superato per client {} ({} {} - {}/{} req/min)",
                    clientIp, method, httpRequest.getRequestURI(), currentCount, maxAllowed);
            httpResponse.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            httpResponse.setContentType("application/json");
            httpResponse.setHeader("Retry-After", "60");
            httpResponse.getWriter().write(
                    "{\"status\":429,\"message\":\"Frequenza di richieste eccessiva. Riprova tra 60 secondi.\"}"
            );
            return;
        }

        chain.doFilter(request, response);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

