package fr.claudegateway.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

import fr.claudegateway.user.UserService;

/**
 * Configuration OAuth2 Client <b>conditionnelle</b> : n'est active que si un identifiant client
 * Google est fourni ({@code app.oauth2.google.client-id}). Sans cette propriété, aucun bean n'est
 * créé : OAuth reste <b>dormant</b> et l'application démarre normalement (pas d'échec sur client-id
 * vide, contrairement à {@code spring.security.oauth2.client.registration.google.*}).
 */
@Configuration
@Conditional(GoogleOAuthConfiguredCondition.class)
public class OAuth2ClientConfig {

    /**
     * Enregistrement Google construit à partir des endpoints standard OIDC ({@link CommonOAuth2Provider})
     * et des identifiants fournis par la configuration (jamais en dur).
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${app.oauth2.google.client-id}") String clientId,
            @Value("${app.oauth2.google.client-secret:}") String clientSecret) {

        ClientRegistration google = CommonOAuth2Provider.GOOGLE
                .getBuilder("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();
        return new InMemoryClientRegistrationRepository(google);
    }

    @Bean
    public OAuth2LoginSuccessHandler oauth2LoginSuccessHandler(
            UserService userService,
            JwtService jwtService,
            @Value("${app.frontend-url:http://localhost:4200}") String frontendUrl) {
        return new OAuth2LoginSuccessHandler(userService, jwtService, frontendUrl);
    }
}
