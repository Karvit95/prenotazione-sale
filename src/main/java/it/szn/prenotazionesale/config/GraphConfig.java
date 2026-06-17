package it.szn.prenotazionesale.config;

import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;

import lombok.AllArgsConstructor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AllArgsConstructor
@Configuration
public class GraphConfig {

	private final AppConfig appConfig;

	@Bean
    GraphServiceClient graphServiceClient() {
        var credential = new ClientSecretCredentialBuilder()
                .tenantId(appConfig.getAzure().getTenantId())
                .clientId(appConfig.getAzure().getClientId())
                .clientSecret(appConfig.getAzure().getClientSecret())
                .build();

        return new GraphServiceClient(credential,
                "https://graph.microsoft.com/.default");
    }
}