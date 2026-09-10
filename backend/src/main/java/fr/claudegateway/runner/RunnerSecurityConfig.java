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
 * <p>Deux entrées : {@code POST /runner/pair} (SF-38-01, authentifié par le code d'appairage porté
 * dans le corps) et le handshake WebSocket {@code GET /runner/ws} (SF-38-02, authentifié par le jeton
 * runner dans {@code RunnerHandshakeInterceptor}), plus le téléchargement du binaire runner
 * {@code GET /runner/download} (SF-38-03, client public sans secret), et le <b>repli long-polling</b>
 * {@code POST /runner/poll|send|disconnect} (SF-38-09, authentifié par l'en-tête
 * {@code X-Runner-Token} directement dans {@code RunnerPollController}), et enfin le <b>relais local
 * {@code px}</b> {@code GET /runner/relay/*} (F-59 / SF-59-01 — un binaire tiers public et sa notice
 * de licence, servis pour l'utilisateur dont le poste n'atteint pas GitHub). Toutes sont
 * {@code permitAll} au niveau de la chaîne (l'authentification réelle est faite en aval) ; tout le
 * reste est refusé. Stateless, CSRF désactivé (API non navigateur, pas de cookie de session).</p>
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
                        // Le handshake WS (SF-38-02) est authentifié par le jeton runner dans
                        // RunnerHandshakeInterceptor (query param ou sous-protocole), pas ici.
                        .requestMatchers(HttpMethod.GET, "/runner/ws").permitAll()
                        // Téléchargement du binaire runner (SF-38-03) : client public, sans secret.
                        .requestMatchers(HttpMethod.GET, "/runner/download").permitAll()
                        // Paquet autonome Windows (F-44 / SF-44-02) : même nature que le jar
                        // — un client public, sans jeton ni secret. L'appairage vient après.
                        .requestMatchers(HttpMethod.GET, "/runner/download/windows").permitAll()
                        // Paquets autonomes macOS (F-44 / SF-44-03), Apple Silicon et Intel : même
                        // nature encore — un client public. Déclarés un par un plutôt qu'en
                        // `/runner/download/**` : une autorisation par joker couvrirait d'avance
                        // toute route future de ce préfixe, y compris celle qui ne devrait pas
                        // l'être.
                        .requestMatchers(HttpMethod.GET, "/runner/download/macos-aarch64").permitAll()
                        .requestMatchers(HttpMethod.GET, "/runner/download/macos-x64").permitAll()
                        .requestMatchers(HttpMethod.GET, "/runner/download/formats").permitAll()
                        // Relais local `px` servi par la gateway (F-59 / SF-59-01) : même nature
                        // encore — un binaire tiers public, sans jeton ni secret. Et l'exiger
                        // authentifié manquerait la cible : celui qui en a besoin est justement
                        // celui dont le poste ne sort pas. Déclarées une par une, comme au-dessus.
                        .requestMatchers(HttpMethod.GET, "/runner/relay/windows").permitAll()
                        .requestMatchers(HttpMethod.GET, "/runner/relay/macos-aarch64").permitAll()
                        .requestMatchers(HttpMethod.GET, "/runner/relay/linux-x64").permitAll()
                        // La notice MIT : servie AVEC les archives, c'est la condition de leur
                        // redistribution — donc aussi publique qu'elles.
                        .requestMatchers(HttpMethod.GET, "/runner/relay/license").permitAll()
                        .requestMatchers(HttpMethod.GET, "/runner/relay/formats").permitAll()
                        // Repli long-polling (SF-38-09) : le jeton runner voyage dans l'en-tête
                        // X-Runner-Token et est vérifié PAR LE CONTRÔLEUR (RunnerPollController) —
                        // aucun filtre HTTP ne sait lire un jeton runner, et rien n'est posé dans le
                        // SecurityContext (D9). Sans ces trois entrées, le denyAll final les refuse.
                        .requestMatchers(HttpMethod.POST, "/runner/poll").permitAll()
                        .requestMatchers(HttpMethod.POST, "/runner/send").permitAll()
                        .requestMatchers(HttpMethod.POST, "/runner/disconnect").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
