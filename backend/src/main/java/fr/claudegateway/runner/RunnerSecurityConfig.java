package fr.claudegateway.runner;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Chaîne de sécurité <b>dédiée au runner</b> (F-38 / SF-38-01, décision D9). Ordonnée avant la
 * chaîne principale et restreinte à {@code /runner/**} via {@code securityMatcher} : un jeton runner
 * (ou son code d'appairage) ne peut jamais authentifier un endpoint utilisateur, et la chaîne
 * principale reste strictement inchangée.
 *
 * <p>SF-38-01 n'expose que {@code POST /runner/pair}, authentifié par le code d'appairage porté dans
 * le corps (pas de JWT). Le canal WebSocket {@code /runner/ws} viendra en SF-38-02 sur cette même
 * chaîne. Stateless, CSRF désactivé (API non navigateur, pas de cookie de session).</p>
 */
@Configuration
public class RunnerSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain runnerSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/runner/**")
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/runner/**").permitAll()
                        // L'appairage s'authentifie par le code porté dans le corps, pas par un JWT.
                        .requestMatchers(HttpMethod.POST, "/runner/pair").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
