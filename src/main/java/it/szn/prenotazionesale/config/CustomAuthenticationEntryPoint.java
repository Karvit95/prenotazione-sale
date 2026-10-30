package it.szn.prenotazionesale.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.szn.prenotazionesale.audit.SecurityAuditService;
import it.szn.prenotazionesale.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityAuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String safeReason = auditService.sanitizeSensitiveText(
                authException != null ? authException.getMessage() : "Unauthorized"
        );
        auditService.logAuthenticationFailure(
                request.getRequestURI(),
                request,
                safeReason
        );

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse error = ErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                "Autenticazione richiesta. Token mancante, scaduto o non valido."
        );
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}

