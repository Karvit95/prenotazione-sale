package it.szn.prenotazionesale.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    public static final String DEFAULT_ADMIN_ROLE = "RoomBooking.Admin";

    private String adminRole;
    private Cors cors = new Cors();
    private Azure azure = new Azure();
    private Security security = new Security();

    /**
     * Risolve il ruolo di amministratore configurato per l'applicazione.
     * Restituisce il valore configurato (con trim) oppure il valore di default
     * {@link #DEFAULT_ADMIN_ROLE} se non specificato o vuoto.
     *
     * @return nome del ruolo admin (senza prefisso ROLE_)
     */
    public String resolveAdminRole() {
        if (adminRole != null && !adminRole.isBlank()) {
            return adminRole.trim();
        }
        return DEFAULT_ADMIN_ROLE;
    }

    @Data
    public static class Security {
        public static final String DEFAULT_TRUSTED_PROXIES =
                "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";
        private String trustedProxies = DEFAULT_TRUSTED_PROXIES;
    }

    @Data
    public static class Cors {
        private String allowedOrigins;
    }

    @Data
    public static class Azure {
        private String tenantId;
        private String clientId;

        @ToString.Exclude
        @EqualsAndHashCode.Exclude
        private String clientSecret;
    }
}
