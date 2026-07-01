package fr.claudegateway.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import fr.claudegateway.shared.error.RestAuthenticationEntryPoint;

/**
 * Configuration Spring Security de la plateforme : <b>stateless</b>, sans session serveur,
 * validation d'un JWT Bearer à chaque requête.
 *
 * <p>Endpoints publics : santé actuator, futurs endpoints d'authentification ({@code /auth/**}),
 * le smoke-test {@code /hello} (préservé pour la page d'accueil frontend), et la console H2 (dev).
 * Tout le reste exige un JWT valide.</p>
 *
 * <p>Note context-path : l'application est servie sous {@code /api}. Les matchers ci-dessous sont
 * relatifs au context-path (déjà retiré par le conteneur), donc {@code /me} correspond à
 * l'URL publique {@code /api/me}. La configuration CORS reste portée par {@code WebConfig} (MVC) ;
 * les pré-vols {@code OPTIONS} sont donc laissés passer jusqu'à la couche MVC.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers("/hello").permitAll()
                        .requestMatchers("/h2-console/**").permitAll()
                        .anyRequest().authenticated())
                // La console H2 (dev) est servie dans une frame de même origine.
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Encodeur de mot de passe de la plateforme : BCrypt (facteur de coût par défaut = 10).
     * Utilisé pour le hachage à l'inscription et la vérification à la connexion. Aucun mot de
     * passe n'est jamais stocké ni comparé en clair.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
