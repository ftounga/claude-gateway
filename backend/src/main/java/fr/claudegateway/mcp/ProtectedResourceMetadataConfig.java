package fr.claudegateway.mcp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Chaîne de sécurité dédiée à la métadonnée de ressource protégée (F-112 / SF-112-02) :
 * {@code GET /.well-known/oauth-protected-resource} est <b>public</b> (RFC 9728 exige une découverte
 * sans authentification), en lecture seule. Restreinte à ce seul chemin par {@code securityMatcher},
 * elle n'ouvre rien d'autre.
 */
@Configuration
public class ProtectedResourceMetadataConfig {

    @Bean
    @Order(3)
    public SecurityFilterChain protectedResourceMetadataFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/.well-known/oauth-protected-resource")
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/.well-known/oauth-protected-resource").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
