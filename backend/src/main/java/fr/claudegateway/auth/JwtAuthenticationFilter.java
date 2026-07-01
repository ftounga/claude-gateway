package fr.claudegateway.auth;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import fr.claudegateway.user.User;
import fr.claudegateway.user.UserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authentifie chaque requête portant un en-tête {@code Authorization: Bearer <jwt>}.
 *
 * <p>En cas de token absent, malformé, expiré, ou pointant sur un utilisateur inexistant,
 * le filtre laisse simplement le contexte de sécurité vide : l'autorisation refusera l'accès
 * et l'{@code AuthenticationEntryPoint} renverra une 401 JSON homogène. Le filtre n'écrit
 * jamais lui-même la réponse d'erreur et ne journalise jamais le token.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserService userService;

    public JwtAuthenticationFilter(JwtService jwtService, UserService userService) {
        this.jwtService = jwtService;
        this.userService = userService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String token = extractBearerToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(token, request);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            Claims claims = jwtService.parseClaims(token);
            UUID userId = UUID.fromString(claims.getSubject());

            userService.findById(userId).ifPresentOrElse(
                    user -> authenticateIfTokenVersionMatches(user, claims, request),
                    () -> log.debug("JWT valide mais utilisateur {} introuvable — accès refusé", userId));
        } catch (JwtException | IllegalArgumentException ex) {
            // Token malformé, signature invalide, expiré, ou sub non-UUID : contexte laissé vide.
            log.debug("Rejet du JWT présenté : {}", ex.getClass().getSimpleName());
        }
    }

    /**
     * N'authentifie que si le claim {@code tv} du token correspond au {@code token_version} courant
     * de l'utilisateur. Après un « logout de toutes les sessions », les tokens antérieurs (ancien
     * {@code tv}) sont ainsi rejetés. Un token sans claim {@code tv} est traité comme {@code tv=0}.
     */
    private void authenticateIfTokenVersionMatches(User user, Claims claims, HttpServletRequest request) {
        Integer tokenVersion = claims.get(JwtService.CLAIM_TOKEN_VERSION, Integer.class);
        int presentedVersion = tokenVersion == null ? 0 : tokenVersion;
        if (presentedVersion != user.getTokenVersion()) {
            log.debug("JWT rejeté : version de token périmée (logout-all) pour l'utilisateur {}", user.getId());
            return;
        }
        setAuthentication(user, request);
    }

    private void setAuthentication(User user, HttpServletRequest request) {
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));

        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
