package fr.claudegateway.mcp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import fr.claudegateway.auth.JwtAuthenticationFilter;
import fr.claudegateway.shared.error.RestAuthenticationEntryPoint;

/**
 * Chaîne de sécurité <b>dédiée au serveur MCP</b> (F-112 / SF-112-01, ADR-020), restreinte à
 * {@code /mcp} via {@code securityMatcher} et ordonnée avant la chaîne principale. Elle garantit
 * qu'un jeton porteur du serveur MCP <b>n'ouvre aucune autre route</b> : cette chaîne ne matche que
 * {@code /mcp*}, et la chaîne principale reste strictement inchangée.
 *
 * <p>En SF-112-01, le porteur accepté est le JWT de la plateforme (interim), validé par
 * {@link JwtAuthenticationFilter} exactement comme sur la chaîne principale. SF-112-02 enrichira la
 * validation du porteur (jetons d'accès OAuth 2.1 liés à la ressource {@code /api/mcp}, audience
 * vérifiée) et SF-112-03 les jetons personnels, sans changer le périmètre de cette chaîne.</p>
 *
 * <p>Stateless, CSRF désactivé (API non navigateur, pas de cookie de session) ; l'entry point 401
 * JSON de la plateforme est conservé pour l'homogénéité des erreurs.</p>
 */
@Configuration
public class McpSecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    public McpSecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Bean
    @Order(2)
    public SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/mcp", "/mcp/**")
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/mcp", "/mcp/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
