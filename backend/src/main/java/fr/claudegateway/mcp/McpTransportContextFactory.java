package fr.claudegateway.mcp;

import java.util.Map;

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
 * <p>L'authentification réelle est faite en amont par la chaîne de sécurité dédiée {@code /mcp}
 * (voir {@code McpSecurityConfig}). Ici on ne fait que lire ce qui a déjà été validé — jamais un
 * paramètre de la requête.</p>
 */
@Component
public class McpTransportContextFactory implements McpTransportContextExtractor<ServerRequest> {

    @Override
    public McpTransportContext extract(ServerRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return McpTransportContext.create(Map.of(McpCallContext.KEY, new McpCallContext(user)));
        }
        return McpTransportContext.EMPTY;
    }
}
