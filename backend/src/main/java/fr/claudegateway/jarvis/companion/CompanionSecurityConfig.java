package fr.claudegateway.jarvis.companion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Chaîne de sécurité <b>dédiée au compagnon Android</b> (F-232 / SF-232-03), même patron que celle du runner
 * (D9 de F-38) : restreinte à {@code /companion/**}, ordonnée avant la chaîne principale. Deux routes, déclarées
 * une par une — l'appairage (authentifié par le code porté dans le corps) et les annonces (authentifiées par le
 * jeton dédié, vérifié par {@link CompanionFeedController}) — et {@code denyAll} pour tout le reste.
 *
 * <p>Conséquences : le jeton du compagnon ne peut authentifier aucune route utilisateur (la chaîne principale ne
 * sait lire que des JWT), et un JWT n'ouvre pas les annonces (le contrôleur n'accepte que le jeton dédié).
 * Préfixes disjoints de {@code /internal/**} (ordre 0), {@code /runner/**} (ordre 1) et {@code /mcp/**}
 * (ordre 4).</p>
 */
@Configuration
public class CompanionSecurityConfig {

    @Bean
    @Order(2)
    public SecurityFilterChain companionSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/companion/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Le code d'appairage, porté dans le corps, est la seule preuve : pas de JWT.
                        .requestMatchers(HttpMethod.POST, "/companion/pair").permitAll()
                        // Le jeton dédié (X-Companion-Token) est vérifié PAR LE CONTRÔLEUR ; rien dans le
                        // SecurityContext.
                        .requestMatchers(HttpMethod.GET, "/companion/announcements").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
