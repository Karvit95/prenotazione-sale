package it.szn.prenotazionesale.exception;

import com.microsoft.kiota.ApiException;
import com.microsoft.kiota.ResponseHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    private static class MockApiException extends ApiException {
        public MockApiException(int statusCode, ResponseHeaders headers) {
            super("API Exception: " + statusCode);
            setResponseStatusCode(statusCode);
            setResponseHeaders(headers);
        }
    }

    @Test
    void testHandleGraphApiException_429WithRetryAfter() {
        ResponseHeaders headers = new ResponseHeaders();
        headers.add("Retry-After", "12");
        ApiException ex = new MockApiException(429, headers);

        ResponseEntity<ErrorResponse> response = handler.handleGraphApiException(ex);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("12", response.getHeaders().getFirst("Retry-After"));
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("12 secondi"));
    }

    @Test
    void testHandleGraphApiException_Non429ReturnsBadGateway() {
        ApiException ex = new MockApiException(503, new ResponseHeaders());

        ResponseEntity<ErrorResponse> response = handler.handleGraphApiException(ex);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("Errore di comunicazione"));
    }

    @Test
    void testHandleCompletionException_UnwrapsApiException() {
        ResponseHeaders headers = new ResponseHeaders();
        headers.add("Retry-After", "5");
        ApiException inner = new MockApiException(429, headers);
        CompletionException ex = new CompletionException(inner);

        ResponseEntity<ErrorResponse> response = handler.handleCompletionException(ex);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void testHandleCompletionException_UnwrapsTimeoutException() {
        TimeoutException inner = new TimeoutException("Graph call timed out");
        CompletionException ex = new CompletionException(inner);

        ResponseEntity<ErrorResponse> response = handler.handleCompletionException(ex);

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("scaduta"));
    }
}

