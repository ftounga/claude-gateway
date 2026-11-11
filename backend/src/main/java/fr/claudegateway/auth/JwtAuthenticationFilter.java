package fr.claudegateway.auth;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 *
 * <p><b>F-232 / SF-232-05 — la session du compagnon.</b> Un jeton portant la portée {@code jarvis-compagnon} n'ouvre que
 * la liste fermée des routes de l'écran Jarvis ({@link CompanionSessionVerifier#allows}) : hors liste, le filtre répond
 * lui-même <b>403</b> {@code jarvis_companion_scope} (seule exception à la règle ci-dessus). Le compagnon doit être
 * encore appairé à chaque requête (révocation immédiate) ; la version de jeton s'applique comme partout. Toute autre
 * portée est refusée.</p>
 *
 * <p><b>F-257 / SF-257-02 — le ticket de session vocale.</b> La portée {@code jarvis-voice} n'ouvre que
 * {@link VoiceTicketScope} : hors liste, 403 {@code jarvis_voice_scope} ; la version de jeton s'applique comme partout.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserService userService;
    /** F-232 / SF-232-05 — absent (contexte partiel de test) : toute session à portée est refusée. */
    private final ObjectProvider<CompanionSessionVerifier> companionVerifier;

    public JwtAuthenticationFilter(JwtService jwtService, UserService userService,
            ObjectProvider<CompanionSessionVerifier> companionVerifier) {
        this.jwtService = jwtService;
        this.userService = userService;
        this.companionVerifier = companionVerifier;
    }

    /** Ce que le filtre a conclu du jeton présenté. */
    private enum Outcome { CONTINUE, OUT_OF_SCOPE, OUT_OF_VOICE_SCOPE }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String token = extractBearerToken(request);
        Outcome outcome = token != null && SecurityContextHolder.getContext().getAuthentication() == null
                ? authenticate(token, request) : Outcome.CONTINUE;
        if (outcome == Outcome.OUT_OF_VOICE_SCOPE) {
            // F-257 / SF-257-02 : un ticket de session vocale hors de sa liste fermée — refus net, jamais une 401.
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"jarvis_voice_scope\","
                    + "\"message\":\"Cette route n'est pas ouverte au ticket de session vocale.\"}");
            return;
        }
        if (outcome == Outcome.OUT_OF_SCOPE) {
            // F-232 / SF-232-05 : une session « jarvis-compagnon » hors de la liste fermée — refus net, jamais
            // une 401 (l'écran du compagnon ne doit pas croire sa session expirée et la renouveler en boucle).
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"jarvis_companion_scope\","
                    + "\"message\":\"Cette route n'est pas ouverte à la session du compagnon.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private Outcome authenticate(String token, HttpServletRequest request) {
        try {
            Claims claims = jwtService.parseClaims(token);
            UUID userId = UUID.fromString(claims.getSubject());
            String scope = claims.get(JwtService.CLAIM_SCOPE, String.class);
            CompanionSession companion = null;
            if (JwtService.SCOPE_JARVIS_VOICE.equals(scope)) {
                // F-257 / SF-257-02 — le ticket de session vocale : sa liste fermée, puis la version de jeton.
                if (!VoiceTicketScope.allows(request.getMethod(), pathWithinApplication(request))) {
                    return Outcome.OUT_OF_VOICE_SCOPE;
                }
            } else if (scope != null) {
                CompanionSessionVerifier verifier = companionVerifier.getIfAvailable();
                if (!JwtService.SCOPE_JARVIS_COMPANION.equals(scope) || verifier == null) {
                    log.debug("JWT rejeté : portée inconnue");
                    return Outcome.CONTINUE;
                }
                if (!verifier.allows(request.getMethod(), pathWithinApplication(request))) {
                    return Outcome.OUT_OF_SCOPE;
                }
                UUID companionId = UUID.fromString(claims.get(JwtService.CLAIM_COMPANION, String.class));
                if (!verifier.isActive(userId, companionId)) {
                    log.debug("JWT rejeté : compagnon révoqué ou inconnu");
                    return Outcome.CONTINUE;
                }
                // Absent ⇒ verrouillé : le défaut sûr (SF-232-06).
                Boolean locked = claims.get(JwtService.CLAIM_LOCKED, Boolean.class);
                companion = new CompanionSession(companionId, locked == null || locked);
            }
            CompanionSession details = companion;
            userService.findById(userId).ifPresentOrElse(
                    user -> authenticateIfTokenVersionMatches(user, claims, request, details),
                    () -> log.debug("JWT valide mais utilisateur {} introuvable — accès refusé", userId));
        } catch (JwtException | IllegalArgumentException | NullPointerException ex) {
            // Token malformé, signature invalide, expiré, sub ou cid non-UUID : contexte laissé vide.
            log.debug("Rejet du JWT présenté : {}", ex.getClass().getSimpleName());
        }
        return Outcome.CONTINUE;
    }

    /** Le chemin sans le context-path ({@code /api/jarvis/converse} ⇒ {@code /jarvis/converse}). */
    static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
        String context = request.getContextPath() == null ? "" : request.getContextPath();
        return !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    /**
     * N'authentifie que si le claim {@code tv} du token correspond au {@code token_version} courant
     * de l'utilisateur. Après un « logout de toutes les sessions », les tokens antérieurs (ancien
     * {@code tv}) sont ainsi rejetés. Un token sans claim {@code tv} est traité comme {@code tv=0}.
     */
    private void authenticateIfTokenVersionMatches(User user, Claims claims, HttpServletRequest request,
            CompanionSession companion) {
        Integer tokenVersion = claims.get(JwtService.CLAIM_TOKEN_VERSION, Integer.class);
        int presentedVersion = tokenVersion == null ? 0 : tokenVersion;
        if (presentedVersion != user.getTokenVersion()) {
            log.debug("JWT rejeté : version de token périmée (logout-all) pour l'utilisateur {}", user.getId());
            return;
        }
        setAuthentication(user, request, companion);
    }

    private void setAuthentication(User user, HttpServletRequest request, CompanionSession companion) {
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));

        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        // F-232 / SF-232-05 : une session du compagnon porte ses détails (compagnon, verrouillé) ; les autres, ceux du web.
        authentication.setDetails(companion != null ? companion
                : new WebAuthenticationDetailsSource().buildDetails(request));
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
