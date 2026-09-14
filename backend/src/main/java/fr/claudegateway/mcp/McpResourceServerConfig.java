package fr.claudegateway.mcp;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Serveur de ressources OAuth 2.1 pour {@code /api/mcp} (F-112 / SF-112-02, ADR-020). Remplace la
 * validation JWT plateforme interim de SF-112-01 : la chaîne dédiée {@code /mcp} n'accepte désormais
 * que des <b>jetons d'accès OAuth</b> émis par le serveur d'autorisation embarqué, dont la signature
 * (clés rotables du {@link McpJwkSource}), l'expiration et l'<b>audience</b> (RFC 8707) sont vérifiées.
 *
 * <p>Un jeton MCP n'ouvre <b>aucune</b> route hors {@code /mcp} : cette chaîne ne matche que
 * {@code /mcp*}, et le décodeur/validateur est propre à cette chaîne. Sans jeton, la réponse est un
 * {@code 401} avec {@code WWW-Authenticate} pointant la métadonnée de ressource protégée (RFC 9728).</p>
 */
@Configuration
public class McpResourceServerConfig {

    @Bean
    @Order(4)
    public SecurityFilterChain mcpResourceServerFilterChain(
            HttpSecurity http,
            JWKSource<SecurityContext> jwkSource,
            McpResourceProperties resourceProperties,
            McpJwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {

        NimbusJwtDecoder jwtDecoder = mcpJwtDecoder(jwkSource, resourceProperties);
        AuthenticationEntryPoint entryPoint = resourceMetadataEntryPoint();

        http
                .securityMatcher("/mcp", "/mcp/**")
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/mcp", "/mcp/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(new BearerTokenAccessDeniedHandler())
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint));

        return http.build();
    }

    /**
     * Décodeur des jetons d'accès MCP : signature vérifiée via le {@link JWKSource} (rotation par
     * {@code kid}), validateurs par défaut (dont l'expiration) + audience = ressource MCP.
     */
    private NimbusJwtDecoder mcpJwtDecoder(
            JWKSource<SecurityContext> jwkSource, McpResourceProperties resourceProperties) {
        JWSKeySelector<SecurityContext> keySelector =
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource);
        DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
        jwtProcessor.setJWSKeySelector(keySelector);

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(jwtProcessor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new McpAudienceValidator(resourceProperties.resource())));
        return decoder;
    }

    /**
     * Entry point 401 conforme RFC 9728 : {@code WWW-Authenticate: Bearer resource_metadata="…"} qui
     * pointe la métadonnée de ressource protégée, d'où le client découvre le serveur d'autorisation.
     */
    private AuthenticationEntryPoint resourceMetadataEntryPoint() {
        return (HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
                org.springframework.security.core.AuthenticationException authException) -> writeChallenge(request, response);
    }

    private void writeChallenge(HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) throws IOException {
        String metadataUrl = ServletUriComponentsBuilder.fromContextPath(request)
                .path("/.well-known/oauth-protected-resource")
                .build().toUriString();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer resource_metadata=\"" + metadataUrl + "\"");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }
}
