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

    private String adminRole;
    private Cors cors = new Cors();
    private Azure azure = new Azure();
    private Security security = new Security();

    @Data
    public static class Security {
        private String trustedProxies = "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";
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
