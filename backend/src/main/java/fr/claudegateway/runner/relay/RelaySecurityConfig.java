package fr.claudegateway.runner.relay;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Chaîne de sécurité <b>dédiée au relais interne</b> (F-38 / SF-38-12), restreinte à
 * {@code /internal/**} et ordonnée avant les chaînes runner ({@code @Order(1)}) et principale.
 *
 * <p>Sans elle, {@code SecurityConfig.anyRequest().authenticated()} répondrait une 401 JSON de type
 * « JWT manquant » et masquerait le contrat : le contrôleur interne ne serait jamais atteint, même
 * avec le bon secret. Le {@code permitAll} n'ouvre rien — l'authentification est le secret partagé,
 * déjà vérifié en amont par {@link RunnerRelayAuthFilter}, qui s'exécute en
 * {@code HIGHEST_PRECEDENCE} et rejette avant toute chaîne de sécurité.</p>
 *
 * <p>Stateless, CSRF et CORS désactivés : trafic pod-à-pod, jamais un navigateur, jamais un
 * cookie.</p>
 */
@Configuration
@Conditional(RunnerRelayEnabledCondition.class)
public class RelaySecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain relaySecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/internal/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
