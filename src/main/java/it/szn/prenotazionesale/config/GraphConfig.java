package it.szn.prenotazionesale.config;

import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.core.requests.GraphClientFactory;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.authentication.AzureIdentityAuthenticationProvider;
import com.microsoft.kiota.http.OkHttpRequestAdapter;
import lombok.AllArgsConstructor;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configurazione del client Microsoft Graph SDK.
 * Configura le credenziali Azure Entra ID (Client Credentials Flow)
 * e un client HTTP OkHttpClient dedicato con timeout espliciti per prevenire blocchi di rete.
 */
@AllArgsConstructor
@Configuration
public class GraphConfig {

    private final AppConfig appConfig;

    /**
     * Client HTTP OkHttpClient configurato con timeout mirati per prevenire thread bloccati
     * a tempo indefinito in caso di latenza di rete o interruzioni TCP verso Microsoft 365.
     */
    @Bean
    public OkHttpClient graphOkHttpClient() {
        return GraphClientFactory.create()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(15))
                .writeTimeout(Duration.ofSeconds(10))
                .callTimeout(Duration.ofSeconds(25))
                .build();
    }

    /**
     * Istanza del GraphServiceClient che utilizza l'adapter OkHttp dotato di timeout configurati.
     */
    @Bean
    public GraphServiceClient graphServiceClient(OkHttpClient graphOkHttpClient) {
        var credential = new ClientSecretCredentialBuilder()
                .tenantId(appConfig.getAzure().getTenantId())
                .clientId(appConfig.getAzure().getClientId())
                .clientSecret(appConfig.getAzure().getClientSecret())
                .build();

        var authProvider = new AzureIdentityAuthenticationProvider(
                credential,
                new String[]{"graph.microsoft.com"},
                "https://graph.microsoft.com/.default");

        var adapter = new OkHttpRequestAdapter(authProvider, null, null, graphOkHttpClient);
        return new GraphServiceClient(adapter);
    }
}