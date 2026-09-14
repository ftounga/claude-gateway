package fr.claudegateway.mcp.token;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.mcp.McpAuthDetails;
import fr.claudegateway.mcp.McpAuthKind;
import fr.claudegateway.user.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authentifie un <b>jeton personnel</b> MCP présenté sur {@code /mcp} (F-112 / SF-112-03). Ne traite
 * que les porteurs au format {@code cgmcp_…} : tout autre porteur (jeton d'accès OAuth) est laissé au
 * serveur de ressources en aval. Un jeton personnel n'ouvre donc que {@code /api/mcp} et rien
 * d'autre.
 *
 * <p>Un jeton révoqué ou expiré est refusé (401) ; au-delà de la limite par jeton, refusé (429). En
 * cas de succès, l'identité, les périmètres et les postes accessibles sont posés dans le contexte de
 * sécurité (détails {@link McpAuthDetails}), lus ensuite par le contexte d'appel MCP.</p>
 */
@Component
public class McpPersonalTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final McpPersonalTokenService tokenService;
    private final UserService userService;
    private final McpRateLimiter rateLimiter;

    public McpPersonalTokenAuthenticationFilter(
            McpPersonalTokenService tokenService, UserService userService, McpRateLimiter rateLimiter) {
        this.tokenService = tokenService;
        this.userService = userService;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String bearer = extractBearer(request);
        if (!McpPersonalTokenService.looksLikePersonalToken(bearer)
                || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        McpPersonalToken token = tokenService.authenticate(bearer).orElse(null);
        if (token == null) {
            unauthorized(response, "Jeton personnel invalide, révoqué ou expiré.");
            return;
        }
        if (!rateLimiter.tryAcquire(token.getId())) {
            tooManyRequests(response);
            return;
        }
        AuthenticatedUser principal = userService.findById(token.getUserId())
                .map(user -> new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole()))
                .orElse(null);
        if (principal == null) {
            unauthorized(response, "Compte introuvable pour ce jeton.");
            return;
        }

        Set<String> scopes = scopesOf(token);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities(scopes));
        authentication.setDetails(new McpAuthDetails(
                McpAuthKind.PERSONAL, token.getId(), token.getName(), scopes, token.getHostIds()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private Set<String> scopesOf(McpPersonalToken token) {
        String scopes = token.getScopes();
        if (scopes == null || scopes.isBlank()) {
            return Set.of();
        }
        return Set.of(scopes.trim().split(" "));
    }

    private List<GrantedAuthority> authorities(Set<String> scopes) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        scopes.forEach(s -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + s)));
        return authorities;
    }

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String value = header.substring(BEARER_PREFIX.length()).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"invalid_token\",\"message\":\"" + message + "\"}");
    }

    private void tooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"rate_limited\",\"message\":\"Limite de 60 appels par minute atteinte pour ce jeton.\"}");
    }
}
