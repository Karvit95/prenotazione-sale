package it.szn.prenotazionesale.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import lombok.AllArgsConstructor;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@AllArgsConstructor
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final AppConfig appConfig;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 1. CORS restrittivo e sicuro
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // 2. Disabilitazione CSRF sicura (API 100% Stateless basata su Bearer Token)
            .csrf(csrf -> csrf.disable())

            // 3. Imposizione rigida dell'assenza di stato: nessuna HttpSession memorizzata
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 4. Header di sicurezza HTTP (Anti-Clickjacking, NoSniff, CSP, Referrer-Policy)
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(Customizer.withDefaults())
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
                )
            )

            // 5. Regole di autorizzazione perimetrali su ruoli ed endpoint
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/prenotazioni/**").hasRole("RoomBooking.Admin")
                .requestMatchers(HttpMethod.PATCH, "/api/prenotazioni/**").hasRole("RoomBooking.Admin")
                .requestMatchers(HttpMethod.DELETE, "/api/prenotazioni/**").hasRole("RoomBooking.Admin")
                .requestMatchers("/api/sale/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/prenotazioni/**").authenticated()
                .anyRequest().authenticated()
            )

            // 6. Intercettazione e audit dei rifiuti 401 e 403 a livello di servlet filter
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )

            // 7. Configurazione Resource Server OAuth2 con JwtDecoder hardened
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            );

        return http.build();
    }

    /**
     * Bean JwtDecoder con validazione crittografica e verifica obbligatoria di:
     * - Firma digitale JWKS Entra ID
     * - Scadenza ed intervallo di validita' (exp, nbf con clock skew 60s)
     * - Emittente tenant (iss)
     * - Audience autorizzata (aud) contro attacchi di Token Substitution / Confused Deputy
     */
    @Bean
    JwtDecoder jwtDecoder() {
        String issuerUri = "https://sts.windows.net/" + appConfig.getAzure().getTenantId() + "/";
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();

        OAuth2TokenValidator<Jwt> timestampValidator = new JwtTimestampValidator(Duration.ofSeconds(60));
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuerUri);

        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> {
            List<String> audience = jwt.getAudience();
            String expectedClientId = appConfig.getAzure().getClientId();
            String expectedAppUri = "api://" + expectedClientId;

            if (audience != null && (audience.contains(expectedClientId) || audience.contains(expectedAppUri))) {
                return OAuth2TokenValidatorResult.success();
            }

            OAuth2Error error = new OAuth2Error(
                "invalid_token",
                "Token JWT rifiutato: audience ('aud') non autorizzata per questa API.",
                null
            );
            return OAuth2TokenValidatorResult.failure(error);
        };

        OAuth2TokenValidator<Jwt> combinedValidator = new DelegatingOAuth2TokenValidator<>(
            timestampValidator,
            issuerValidator,
            audienceValidator
        );

        jwtDecoder.setJwtValidator(combinedValidator);
        return jwtDecoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    /**
     * Configurazione CORS protetta:
     * - Whitelist esplicita degli header consentiti (no wildcard '*')
     * - Disabilitazione credenziali cookie non necessarie (allowCredentials=false)
     * - Caching delle risposte preflight per 3600 secondi (riduzione latenza)
     * - Esposizione dell'header Retry-After per il client frontend in caso di rate limiting
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();

        String allowedOriginsConfig = appConfig.getCors().getAllowedOrigins();
        if (allowedOriginsConfig != null && !allowedOriginsConfig.isBlank()) {
            List<String> origins = Arrays.stream(allowedOriginsConfig.split(","))
                    .map(s -> s.trim())
                    .filter(s -> !s.isEmpty())
                    .toList();
            config.setAllowedOrigins(origins);
        } else {
            config.setAllowedOrigins(List.of("http://localhost:5173"));
        }

        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
            "Authorization",
            "Content-Type",
            "Accept",
            "Origin",
            "X-Requested-With",
            "Access-Control-Request-Method",
            "Access-Control-Request-Headers"
        ));
        config.setExposedHeaders(List.of("Retry-After", "Location"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
