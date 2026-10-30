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

@Slf4j
@Service
public class SecurityAuditService {

    private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger("SECURITY_AUDIT");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public void logSuccess(String action, String targetResource, Jwt jwt, HttpServletRequest request, Map<String, Object> details) {
        logEvent(buildEvent("SECURITY_AUDIT", action, "SUCCESS", targetResource, jwt, request, details));
    }

    public void logAccessDenied(String action, String targetResource, Jwt jwt, HttpServletRequest request, String reason) {
        Map<String, Object> details = Map.of("reason", reason != null ? reason : "Access Denied");
        logEvent(buildEvent("ACCESS_VIOLATION", action, "DENIED", targetResource, jwt, request, details));
    }

    public void logAuthenticationFailure(String targetResource, HttpServletRequest request, String reason) {
        Map<String, Object> details = Map.of("reason", reason != null ? reason : "Unauthorized");
        logEvent(buildEvent("AUTH_FAILURE", "AUTHENTICATE", "FAILURE", targetResource, null, request, details));
    }

    public void logGraphIncident(String incidentType, String resource, int statusCode, String message, String correlationId) {
        SecurityAuditEvent event = SecurityAuditEvent.builder()
                .eventType("GRAPH_INCIDENT")
                .action("GRAPH_API_CALL")
                .outcome("FAILURE")
                .targetResource(resource)
                .correlationId(correlationId)
                .details(Map.of(
                        "incidentType", incidentType,
                        "graphStatusCode", statusCode,
                        "incidentMessage", message != null ? message : ""
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

