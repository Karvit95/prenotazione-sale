package it.szn.prenotazionesale.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.szn.prenotazionesale.audit.SecurityAuditService;
import it.szn.prenotazionesale.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityAuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Jwt jwt = (auth != null && auth.getPrincipal() instanceof Jwt jwtPrincipal) ? jwtPrincipal : null;

        auditService.logAccessDenied(
                request.getMethod() + " " + request.getRequestURI(),
                request.getRequestURI(),
                jwt,
                request,
                accessDeniedException.getMessage()
        );

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse error = ErrorResponse.of(
                HttpStatus.FORBIDDEN.value(),
                "Non si dispone dei privilegi necessari per eseguire l'operazione richiesta."
        );
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}

