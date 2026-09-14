package fr.claudegateway.mcp;

import java.util.Map;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;

import fr.claudegateway.auth.AuthenticatedUser;

/**
 * Capture l'identité authentifiée depuis le {@code SecurityContext} <b>sur le thread servlet</b> qui
 * traite la requête MCP, et la dépose dans le {@link McpTransportContext} du SDK. Les outils la
 * relisent ensuite via {@link McpCallContext}, quel que soit le thread sur lequel le SDK exécute
 * l'appel.
 *
 * <p>L'authentification réelle est faite en amont par la chaîne de sécurité dédiée {@code /mcp} :
 * jeton d'accès OAuth (SF-112-02) ou jeton personnel (SF-112-03). Le porteur pose ses détails
 * ({@link McpAuthDetails}) comme {@code details} de l'{@code Authentication} ; on ne lit jamais un
 * paramètre de la requête.</p>
 */
@Component
public class McpTransportContextFactory implements McpTransportContextExtractor<ServerRequest> {

    @Override
    public McpTransportContext extract(ServerRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return McpTransportContext.EMPTY;
        }

        McpAuthKind authKind = McpAuthKind.OAUTH;
        java.util.UUID tokenId = null;
        String clientLabel = "Client MCP";
        Set<String> scopes = Set.of();
        Set<java.util.UUID> hostIds = Set.of();
        if (authentication.getDetails() instanceof McpAuthDetails details) {
            authKind = details.authKind();
            tokenId = details.tokenId();
            clientLabel = details.clientLabel();
            scopes = details.scopes() == null ? Set.of() : details.scopes();
            hostIds = details.hostIds() == null ? Set.of() : details.hostIds();
        }

        McpCallContext context = new McpCallContext(user, authKind, tokenId, clientLabel, scopes, hostIds);
        return McpTransportContext.create(Map.of(McpCallContext.KEY, context));
    }
}
