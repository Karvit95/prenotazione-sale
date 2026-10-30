package it.szn.prenotazionesale.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
public class SecurityAuditService {

    private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger("SECURITY_AUDIT");
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile(
            "(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern RAW_JWT_PATTERN = Pattern.compile(
            "eyJ[A-Za-z0-9_-]{10,}(?:\\.[A-Za-z0-9_-]*)*");
    private static final Pattern SECRET_KV_PATTERN = Pattern.compile(
            "(?i)(client_secret|clientSecret|password|secret)\\s*[=:]\\s*[^\\s,;\"']+");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public String sanitizeSensitiveText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String sanitized = BEARER_TOKEN_PATTERN.matcher(text).replaceAll("Bearer [REDACTED_TOKEN]");
        sanitized = RAW_JWT_PATTERN.matcher(sanitized).replaceAll("[REDACTED_JWT]");
        sanitized = SECRET_KV_PATTERN.matcher(sanitized).replaceAll("$1=[REDACTED_SECRET]");
        return sanitized;
    }

    public void logSuccess(String action, String targetResource, Jwt jwt, HttpServletRequest request, Map<String, Object> details) {
        logEvent(buildEvent("SECURITY_AUDIT", action, "SUCCESS", targetResource, jwt, request, details));
    }

    public void logAccessDenied(String action, String targetResource, Jwt jwt, HttpServletRequest request, String reason) {
        String safeReason = sanitizeSensitiveText(reason != null ? reason : "Access Denied");
        Map<String, Object> details = Map.of("reason", safeReason);
        logEvent(buildEvent("ACCESS_VIOLATION", action, "DENIED", targetResource, jwt, request, details));
    }

    public void logAuthenticationFailure(String targetResource, HttpServletRequest request, String reason) {
        String safeReason = sanitizeSensitiveText(reason != null ? reason : "Unauthorized");
        Map<String, Object> details = Map.of("reason", safeReason);
        logEvent(buildEvent("AUTH_FAILURE", "AUTHENTICATE", "FAILURE", targetResource, null, request, details));
    }

    public void logGraphIncident(String incidentType, String resource, int statusCode, String message, String correlationId) {
        String safeMessage = sanitizeSensitiveText(message != null ? message : "");
        SecurityAuditEvent event = SecurityAuditEvent.builder()
                .eventType("GRAPH_INCIDENT")
                .action("GRAPH_API_CALL")
                .outcome("FAILURE")
                .targetResource(resource)
                .correlationId(correlationId)
                .details(Map.of(
                        "incidentType", incidentType,
                        "graphStatusCode", statusCode,
                        "incidentMessage", safeMessage
                ))
                .build();
        logEvent(event);
    }

    private SecurityAuditEvent buildEvent(String type, String action, String outcome,
                                          String targetResource, Jwt jwt,
                                          HttpServletRequest request, Map<String, Object> details) {
        String oid = "ANONYMOUS";
        String upn = "ANONYMOUS";
        String roles = "NONE";

        if (jwt != null) {
            String claimOid = jwt.getClaimAsString("oid");
            oid = (claimOid != null && !claimOid.isBlank()) ? claimOid : jwt.getSubject();
            String preferredUsername = jwt.getClaimAsString("preferred_username");
            String claimUpn = jwt.getClaimAsString("upn");
            upn = (preferredUsername != null && !preferredUsername.isBlank())
                    ? preferredUsername
                    : ((claimUpn != null && !claimUpn.isBlank()) ? claimUpn : "UNKNOWN");
            List<String> roleList = jwt.getClaimAsStringList("roles");
            roles = (roleList != null && !roleList.isEmpty()) ? String.join(",", roleList) : "NONE";
        }

        String clientIp = extractClientIp(request);
        String correlationId = MDC.get("correlationId");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        return SecurityAuditEvent.builder()
                .eventType(type)
                .action(action)
                .outcome(outcome)
                .userObjectId(oid)
                .userPrincipalName(upn)
                .userRoles(roles)
                .clientIp(clientIp)
                .targetResource(targetResource)
                .correlationId(correlationId)
                .details(details != null ? details : Collections.emptyMap())
                .build();
    }

    private void logEvent(SecurityAuditEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            AUDIT_LOGGER.info(json);
        } catch (Exception e) {
            log.error("Errore critico nella serializzazione dell'audit event: {}", e.getMessage());
        }
    }

    public String extractClientIp(HttpServletRequest request) {
        if (request == null) {
            return "UNKNOWN";
        }
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

