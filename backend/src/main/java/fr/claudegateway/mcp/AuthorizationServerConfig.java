package fr.claudegateway.mcp;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import fr.claudegateway.user.UserRepository;

/**
 * Serveur d'autorisation OAuth 2.1 embarqué (F-112 / SF-112-02, ADR-020), pour les clients MCP.
 *
 * <p>Chaîne de filtres <b>dédiée</b> aux endpoints du serveur d'autorisation ({@code /oauth2/*},
 * {@code /connect/register}, {@code /.well-known/oauth-authorization-server}), ordonnée avant la
 * chaîne principale, qui reste inchangée. PKCE obligatoire, code d'autorisation, rafraîchissement
 * rotatif, révocation, {@code iss} dans la réponse (RFC 9207), enregistrement dynamique, écran de
 * consentement, rotation des clés de signature ({@link McpJwkSource}).</p>
 *
 * <p>L'authentification du propriétaire de ressource pour {@code /oauth2/authorize} se fait par
 * session (formulaire de connexion) sur cette chaîne uniquement : la chaîne principale reste
 * <b>stateless</b>. Le raffinement du parcours de connexion navigateur (SSO Google, écran SPA) est un
 * suivi (SF-112-08).</p>
 */
@Configuration
@EnableConfigurationProperties(McpResourceProperties.class)
public class AuthorizationServerConfig {

    /** Client de démonstration/tests. Les clients réels s'ajoutent par enregistrement dynamique. */
    static final String DEMO_CLIENT_ID = "claude-code";
    static final String DEMO_REDIRECT_URI = "https://example.com/callback";

    @Bean
    @Order(2)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();

        http
                .securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
                .with(authorizationServerConfigurer, authorizationServer -> authorizationServer
                        .oidc(oidc -> oidc.clientRegistrationEndpoint(Customizer.withDefaults()))
                        .authorizationEndpoint(authorization ->
                                authorization.consentPage(McpConsentController.CONSENT_PAGE_URI)))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        authorizationServerConfigurer.getEndpointsMatcher()))
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .formLogin(Customizer.withDefaults());

        return http.build();
    }

    /**
     * Registre des clients OAuth. En mémoire en SF-112-02 : un client de démonstration exigeant PKCE
     * et le consentement, plus les clients ajoutés par enregistrement dynamique. La persistance des
     * clients est traitée avec les jetons personnels et le journal (SF-112-03).
     */
    @Bean
    public RegisteredClientRepository registeredClientRepository() {
        RegisteredClient.Builder builder = RegisteredClient.withId(DEMO_CLIENT_ID)
                .clientId(DEMO_CLIENT_ID)
                .clientName("Claude Code (démonstration)")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(DEMO_REDIRECT_URI)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)              // PKCE obligatoire
                        .requireAuthorizationConsent(true)  // écran de consentement
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .reuseRefreshTokens(false)          // rafraîchissement rotatif
                        .build());
        McpScopes.all().forEach(builder::scope);
        return new InMemoryRegisteredClientRepository(builder.build());
    }

    /** Paramètres du serveur d'autorisation : issuer dérivé de la requête (RFC 9207 iss inclus). */
    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
    }

    /** Décodeur JWT utilisé par le serveur d'autorisation (clés du {@link McpJwkSource}). */
    @Bean
    public JwtDecoder authorizationServerJwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /**
     * Ajoute au <b>jeton d'accès</b> l'audience = ressource MCP (RFC 8707) : le serveur de ressources
     * n'accepte que les jetons qui la portent.
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> mcpAccessTokenCustomizer(
            McpResourceProperties resourceProperties) {
        return context -> {
            if ("access_token".equals(context.getTokenType().getValue())) {
                context.getClaims().audience(List.of(resourceProperties.resource()));
            }
        };
    }

    /**
     * Service d'utilisateurs pour la connexion navigateur du parcours OAuth (comptes à mot de passe
     * local). Utilisé uniquement par cette chaîne ; la chaîne principale reste stateless (JWT).
     */
    @Bean
    public UserDetailsService mcpResourceOwnerDetailsService(UserRepository userRepository) {
        return username -> userRepository.findByEmail(username)
                .filter(user -> user.getPasswordHash() != null)
                .map(user -> User.withUsername(user.getEmail())
                        .password(user.getPasswordHash())
                        .authorities("ROLE_" + user.getRole().name())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Utilisateur inconnu : " + username));
    }
}
