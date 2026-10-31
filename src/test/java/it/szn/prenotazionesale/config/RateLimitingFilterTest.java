package it.szn.prenotazionesale.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("RateLimitingFilter - Protezione anti-spoofing e isolamento bucket")
class RateLimitingFilterTest {

    private RateLimitingFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        AppConfig appConfig = new AppConfig();
        ClientIpResolver resolver = new ClientIpResolver(appConfig);
        filter = new RateLimitingFilter(appConfig, resolver);
        filterChain = Mockito.mock(FilterChain.class);
    }

    @Test
    @DisplayName("Da remoteAddr NON fidato, rotazione di XFF spoofato non elude il rate limit: 31esima scrittura riceve 429")
    void testRateLimit_UntrustedRemoteAddr_SpoofedXffCannotBypassLimit() throws ServletException, IOException {
        String untrustedRemoteAddr = "203.0.113.195";

        // Invia 30 richieste di scrittura con XFF sempre diverso
        for (int i = 1; i <= 30; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/prenotazioni");
            request.setRemoteAddr(untrustedRemoteAddr);
            request.addHeader("X-Forwarded-For", "198.51.100." + i);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertEquals(200, response.getStatus(), "La richiesta " + i + " deve essere consentita");
        }

        verify(filterChain, times(30)).doFilter(any(), any());

        // La 31-esima richiesta di scrittura dallo stesso remoteAddr, seppur con XFF ancora diverso, deve essere bloccata con 429
        MockHttpServletRequest request31 = new MockHttpServletRequest("POST", "/api/prenotazioni");
        request31.setRemoteAddr(untrustedRemoteAddr);
        request31.addHeader("X-Forwarded-For", "198.51.100.99");
        MockHttpServletResponse response31 = new MockHttpServletResponse();

        filter.doFilter(request31, response31, filterChain);

        assertEquals(429, response31.getStatus(), "La 31-esima scrittura deve ricevere HTTP 429 Too Many Requests");
        assertTrue(response31.getContentAsString().contains("429"), "La risposta deve contenere il codice di stato 429");
        assertEquals("60", response31.getHeader("Retry-After"));
        verify(filterChain, times(30)).doFilter(any(), any()); // La catena non deve essere invocata per la 31-esima
    }

    @Test
    @DisplayName("Da reverse proxy fidato, client diversi all'estrema destra di XFF hanno bucket di rate limit separati")
    void testRateLimit_TrustedProxy_DistinctClientsHaveSeparateBuckets() throws ServletException, IOException {
        String trustedProxy = "127.0.0.1";
        String clientA = "198.51.100.10";
        String clientB = "198.51.100.20";

        // Client A esaurisce il proprio limite di 30 scritture
        for (int i = 1; i <= 30; i++) {
            MockHttpServletRequest requestA = new MockHttpServletRequest("POST", "/api/prenotazioni");
            requestA.setRemoteAddr(trustedProxy);
            requestA.addHeader("X-Forwarded-For", clientA);
            MockHttpServletResponse responseA = new MockHttpServletResponse();

            filter.doFilter(requestA, responseA, filterChain);
            assertEquals(200, responseA.getStatus());
        }

        // 31-esima richiesta di Client A riceve 429
        MockHttpServletRequest requestA31 = new MockHttpServletRequest("POST", "/api/prenotazioni");
        requestA31.setRemoteAddr(trustedProxy);
        requestA31.addHeader("X-Forwarded-For", clientA);
        MockHttpServletResponse responseA31 = new MockHttpServletResponse();

        filter.doFilter(requestA31, responseA31, filterChain);
        assertEquals(429, responseA31.getStatus(), "Client A deve essere bloccato con 429");

        // Client B invia una richiesta tramite lo stesso proxy: deve passare con 200 perché il bucket è separato
        MockHttpServletRequest requestB = new MockHttpServletRequest("POST", "/api/prenotazioni");
        requestB.setRemoteAddr(trustedProxy);
        requestB.addHeader("X-Forwarded-For", clientB);
        MockHttpServletResponse responseB = new MockHttpServletResponse();

        filter.doFilter(requestB, responseB, filterChain);
        assertEquals(200, responseB.getStatus(), "Client B deve essere consentito indipendentemente da Client A");
    }
}

