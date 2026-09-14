package fr.claudegateway.mcp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.user.UserService;

/**
 * Convertit un jeton d'accès OAuth validé sur {@code /api/mcp} en une {@code Authentication} dont le
 * <b>principal est un {@link AuthenticatedUser}</b> — le même type que sur la chaîne principale.
 *
 * <p>Ainsi l'isolation multi-tenant côté MCP reste inchangée : le {@code user_id} vient du claim
 * {@code sub} du jeton (jamais d'un paramètre), l'utilisateur est chargé pour confirmer qu'il existe
 * toujours, et les périmètres du jeton deviennent des autorités {@code SCOPE_*}. Un jeton dont le
 * {@code sub} ne correspond à aucun utilisateur n'est pas authentifié.</p>
 */
@Component
public class McpJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserService userService;

    public McpJwtAuthenticationConverter(UserService userService) {
        this.userService = userService;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId = parseSubject(jwt.getSubject());
        if (userId == null) {
            throw new McpUnknownSubjectException("Jeton MCP sans sujet exploitable");
        }
        return userService.findById(userId)
                .map(user -> {
                    AuthenticatedUser principal =
                            new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, jwt, authorities(jwt));
                    return (AbstractAuthenticationToken) authentication;
                })
                .orElseThrow(() -> new McpUnknownSubjectException(
                        "Jeton MCP dont le sujet ne correspond à aucun utilisateur"));
    }

    private Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        Object scope = jwt.getClaims().get("scope");
        if (scope instanceof Collection<?> scopes) {
            scopes.forEach(s -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + s)));
        } else if (scope instanceof String scopeString && !scopeString.isBlank()) {
            for (String s : scopeString.split(" ")) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + s));
            }
        }
        return authorities;
    }

    private static UUID parseSubject(String subject) {
        if (subject == null) {
            return null;
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
