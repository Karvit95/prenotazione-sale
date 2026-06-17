package it.szn.prenotazionesale.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppConfig {
	
    private String adminRole;
    private Cors cors = new Cors();
    private Azure azure = new Azure();

    @Data
    public static class Cors {
        private String allowedOrigins;
    }
    
    @Data
    public static class Azure {
        private String tenantId;
        private String clientId;
        private String clientSecret;
    }
}