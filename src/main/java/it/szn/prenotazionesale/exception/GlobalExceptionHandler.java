package it.szn.prenotazionesale.exception;

import com.microsoft.kiota.ApiException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * Controller Advice globale per l'intercettazione centralizzata e la gestione uniforme
 * delle eccezioni applicative, di validazione, di sicurezza e di integrazione con Microsoft Graph.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Errori di business/validazione lanciati esplicitamente dal service
    // (es. orari non validi, sala già occupata)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Richiesta non valida: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
    }

    // Validazione automatica dei DTO (@NotBlank, ecc.)
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
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
    }

    // Stati di inconsistenza che richiedono intervento manuale
    // (es. cambio sala riuscito solo a metà: nuovo evento creato, vecchio non cancellabile)
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        log.error("Stato inconsistente: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), ex.getMessage()));
    }

    // Operazioni riservate (es. solo admin) tentate da utenti senza permesso
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Accesso negato: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(HttpStatus.FORBIDDEN.value(), "Non hai i permessi per eseguire questa operazione."));
    }

    /**
     * Gestione degli errori restituiti dalle API Microsoft Graph (tramite Kiota ApiException).
     * In caso di rate limiting (HTTP 429), propaga l'header Retry-After indicando al client
     * quanti secondi attendere prima di ripetere la richiesta.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleGraphApiException(ApiException ex) {
        int statusCode = ex.getResponseStatusCode();
        if (statusCode == 429) {
            String retryAfter = null;
            if (ex.getResponseHeaders() != null && ex.getResponseHeaders().containsKey("Retry-After")) {
                var headers = ex.getResponseHeaders().get("Retry-After");
                if (headers != null && !headers.isEmpty()) {
                    retryAfter = headers.iterator().next();
                }
            }
            log.warn("Microsoft Graph API rate limit (429) raggiunto. Retry-After: {}s", retryAfter);
            var responseBuilder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
            if (retryAfter != null) {
                responseBuilder.header("Retry-After", retryAfter);
            }
            String msg = (retryAfter != null)
                    ? "Troppe richieste verso Microsoft Graph API. Riprova tra " + retryAfter + " secondi."
                    : "Troppe richieste verso Microsoft Graph API. Riprova tra pochi secondi.";
            return responseBuilder.body(ErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS.value(), msg));
        }
        log.error("Errore Microsoft Graph API (status {}): {}", statusCode, ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of(HttpStatus.BAD_GATEWAY.value(), "Errore di comunicazione con Microsoft Graph: " + ex.getMessage()));
    }

    /**
     * Gestione delle eccezioni derivanti da esecuzioni asincrone (es. chiamate parallele a Graph).
     * Estrae la causa originaria e delega al gestore specifico (ApiException, TimeoutException o generico).
     */
    @ExceptionHandler(CompletionException.class)
    public ResponseEntity<ErrorResponse> handleCompletionException(CompletionException ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof ApiException apiException) {
            return handleGraphApiException(apiException);
        }
        if (cause instanceof TimeoutException) {
            log.error("Timeout durante l'interrogazione concorrente di Microsoft Graph: {}", cause.getMessage());
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(ErrorResponse.of(HttpStatus.GATEWAY_TIMEOUT.value(), "La richiesta a Microsoft Graph è scaduta. Riprova più tardi."));
        }
        if (cause instanceof Exception exception) {
            return handleGeneric(exception);
        }
        return handleGeneric(new Exception(cause));
    }

    // Tutto il resto: errori imprevisti, non esponiamo dettagli interni al client
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Errore interno non gestito", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Si è verificato un errore interno. Riprova più tardi."));
    }
}
