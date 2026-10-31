package it.szn.prenotazionesale.exception;

import com.microsoft.kiota.ApiException;
import it.szn.prenotazionesale.audit.SecurityAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * Controller Advice globale per l'intercettazione centralizzata e la gestione uniforme
 * delle eccezioni applicative, di validazione, di sicurezza e di integrazione con Microsoft Graph.
 * Garantisce assenza di Information Disclosure (SEC-09) e classificazione incidenti NIS 2 (SEC-11).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final SecurityAuditService auditService;

    @Autowired
    public GlobalExceptionHandler(SecurityAuditService auditService) {
        this.auditService = auditService;
    }

    public GlobalExceptionHandler() {
        this.auditService = new SecurityAuditService();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Richiesta non valida: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse("Dati della richiesta non validi.");
        log.warn("Validazione fallita: {}", message);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("Violazione di vincolo: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(),
                        "Violazione dei vincoli di validazione della richiesta."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Payload JSON malformato o tipi di dati incompatibili nella richiesta.");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(),
                        "Formato del payload JSON non valido o tipi di dati incompatibili."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("Metodo HTTP non supportato richiesto: {}", ex.getMethod());
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of(HttpStatus.METHOD_NOT_ALLOWED.value(),
                        "Metodo HTTP non supportato per questo endpoint."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        String correlationId = UUID.randomUUID().toString();
        String uri = request != null ? request.getRequestURI() : "INTERNAL";
        log.error("[CorrID: {}] Stato inconsistente: {}", correlationId, ex.getMessage());
        auditService.logGraphIncident("STATE_CONFLICT_OR_ROLLBACK", uri, HttpStatus.CONFLICT.value(),
                ex.getMessage(), correlationId);
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), ex.getMessage()));
    }

    public ResponseEntity<ErrorResponse> handleGraphPagination(GraphPaginationException ex) {
        return handleGraphPagination(ex, null);
    }

    @ExceptionHandler(GraphPaginationException.class)
    public ResponseEntity<ErrorResponse> handleGraphPagination(GraphPaginationException ex, HttpServletRequest request) {
        String correlationId = UUID.randomUUID().toString();
        String uri = request != null ? request.getRequestURI() : "/api/prenotazioni";
        log.error("[CorrID: {}] Superato il limite di paginazione Microsoft Graph: {}",
                correlationId, auditService.sanitizeSensitiveText(ex.getMessage()));
        auditService.logGraphIncident("GRAPH_PAGINATION_LIMIT", uri, HttpStatus.BAD_GATEWAY.value(),
                ex.getMessage(), correlationId);
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of(HttpStatus.BAD_GATEWAY.value(),
                        "Impossibile leggere il calendario della sala al momento. Riprova più tardi."));
    }

    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return handleAccessDenied(ex, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Accesso negato: {}", ex.getMessage());
        Jwt jwt = null;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt principalJwt) {
            jwt = principalJwt;
        }
        String uri = request != null ? request.getRequestURI() : "INTERNAL";
        String method = request != null ? request.getMethod() : "ACCESS";
        auditService.logAccessDenied(method + " " + uri, uri, jwt, request, ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(HttpStatus.FORBIDDEN.value(),
                        "Non hai i permessi per eseguire questa operazione."));
    }

    /**
     * Overload retrocompatibile per i test unitari diretti.
     */
    public ResponseEntity<ErrorResponse> handleGraphApiException(ApiException ex) {
        return handleGraphApiException(ex, null);
    }

    /**
     * Gestione degli errori restituiti dalle API Microsoft Graph (tramite Kiota ApiException).
     * Classifica gli incidenti per il SIEM (NIS 2 Art. 21/25) e impedisce l'esposizione di
     * messaggi diagnostici grezzi al client (SEC-09).
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleGraphApiException(ApiException ex, HttpServletRequest request) {
        int statusCode = ex.getResponseStatusCode();
        String correlationId = UUID.randomUUID().toString();
        String uri = request != null ? request.getRequestURI() : "/api/prenotazioni";

        if (statusCode == 429) {
            String retryAfter = extractRetryAfter(ex);
            log.warn("[CorrID: {}] Microsoft Graph API rate limit (429) raggiunto. Retry-After: {}s",
                    correlationId, retryAfter);
            auditService.logGraphIncident("RATE_LIMIT_EXCEEDED", uri, statusCode,
                    "Throttling Microsoft Graph - RetryAfter: " + retryAfter, correlationId);

            var responseBuilder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
            if (retryAfter != null) {
                responseBuilder.header("Retry-After", retryAfter);
            }
            String msg = (retryAfter != null)
                    ? "Troppe richieste verso Microsoft Graph API. Riprova tra " + retryAfter + " secondi."
                    : "Troppe richieste verso Microsoft Graph API. Riprova tra pochi secondi.";
            return responseBuilder.body(ErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS.value(), msg));
        }

        if (statusCode == 401 || statusCode == 403) {
            log.error("[CRITICAL INCIDENT - CorrID: {}] Errore autorizzativo con Microsoft Graph (status {}). Verificare client secret e permessi tenant.",
                    correlationId, statusCode);
            auditService.logGraphIncident("GRAPH_AUTH_FAILURE", uri, statusCode,
                    "Fallimento credenziali daemon Graph", correlationId);

            return ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(ErrorResponse.of(HttpStatus.BAD_GATEWAY.value(),
                            "Errore di comunicazione e autenticazione verso il servizio cloud (Rif: " + correlationId + ")."));
        }

        log.error("[CorrID: {}] Errore Microsoft Graph API (status {}): {}",
                correlationId, statusCode, auditService.sanitizeSensitiveText(ex.getMessage()));
        auditService.logGraphIncident("GRAPH_REMOTE_ERROR", uri, statusCode,
                "Errore remoto Graph HTTP " + statusCode, correlationId);

        // Sanificazione: nessun ex.getMessage() grezzo propagato al client
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of(HttpStatus.BAD_GATEWAY.value(),
                        "Errore di comunicazione con il servizio di calendario esterno Microsoft 365 (Rif: " + correlationId + ")."));
    }

    /**
     * Overload retrocompatibile per i test unitari diretti.
     */
    public ResponseEntity<ErrorResponse> handleCompletionException(CompletionException ex) {
        return handleCompletionException(ex, null);
    }

    @ExceptionHandler(CompletionException.class)
    public ResponseEntity<ErrorResponse> handleCompletionException(CompletionException ex, HttpServletRequest request) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof ApiException apiException) {
            return handleGraphApiException(apiException, request);
        }
        if (cause instanceof TimeoutException) {
            String correlationId = UUID.randomUUID().toString();
            String uri = request != null ? request.getRequestURI() : "/api/prenotazioni/tutte";
            log.error("[CorrID: {}] Timeout durante l'interrogazione concorrente di Microsoft Graph: {}",
                    correlationId, cause.getMessage());
            auditService.logGraphIncident("GRAPH_TIMEOUT", uri, HttpStatus.GATEWAY_TIMEOUT.value(),
                    "Timeout interrogazione concorrente Graph", correlationId);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(ErrorResponse.of(HttpStatus.GATEWAY_TIMEOUT.value(),
                            "La richiesta a Microsoft Graph è scaduta. Riprova più tardi."));
        }
        if (cause instanceof Exception exception) {
            return handleGeneric(exception);
        }
        return handleGeneric(new Exception(cause));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Errore interno non gestito", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Si è verificato un errore interno. Riprova più tardi."));
    }

    private String extractRetryAfter(ApiException ex) {
        if (ex.getResponseHeaders() != null && ex.getResponseHeaders().containsKey("Retry-After")) {
            var headers = ex.getResponseHeaders().get("Retry-After");
            if (headers != null && !headers.isEmpty()) {
                return headers.iterator().next();
            }
        }
        return null;
    }
}
