package it.szn.prenotazionesale.audit;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

@Getter
@Builder
public class SecurityAuditEvent {

    @Builder.Default
    private final String timestamp = Instant.now().toString();
    private final String eventType;
    private final String action;
    private final String outcome;
    private final String userObjectId;
    private final String userPrincipalName;
    private final String userRoles;
    private final String clientIp;
    private final String targetResource;
    private final String correlationId;
    private final Map<String, Object> details;
}

