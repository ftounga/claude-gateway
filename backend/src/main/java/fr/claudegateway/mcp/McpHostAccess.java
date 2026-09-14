package fr.claudegateway.mcp;

import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * Résout l'<b>accès poste par poste</b> d'un porteur MCP (cadrage F-112 §4, « le point de
 * confiance ») : ce qu'un outil renvoie part chez le fournisseur de l'IA connectée, donc l'accès à un
 * poste est donné poste par poste.
 *
 * <p>Deux origines d'authentification :</p>
 * <ul>
 *   <li><b>Jeton personnel</b> (SF-112-03) : l'accès est restreint à l'ensemble {@code hostIds} du
 *       jeton — un poste non listé n'est pas accessible.</li>
 *   <li><b>OAuth</b> : en fondation, le consentement poste par poste n'est pas encore transmis dans
 *       le contexte ({@code hostIds} vide, cf. {@link McpAuthDetails}). L'accès porte alors sur les
 *       postes <b>possédés</b> par l'utilisateur. L'isolation inter-utilisateurs reste garantie : le
 *       service appelé vérifie toujours la propriété via {@code requireOwned}.</li>
 * </ul>
 *
 * <p>Dans tous les cas, la propriété {@code user_id} est la racine : cet accès ne l'élargit jamais,
 * il ne fait que <b>restreindre</b> à un sous-ensemble.</p>
 */
@Component
public class McpHostAccess {

    private final RunnerHostService hostService;

    public McpHostAccess(RunnerHostService hostService) {
        this.hostService = hostService;
    }

    /**
     * Vrai si le porteur peut accéder au poste. Vérifie d'abord l'accès poste par poste, puis, pour
     * un jeton personnel, que le poste appartient bien à l'utilisateur (les {@code hostIds} du jeton
     * sont validés à la création, mais on reste défensif).
     */
    public boolean canAccess(McpCallContext ctx, UUID hostId) {
        if (hostId == null) {
            return false;
        }
        if (ctx.authKind() == McpAuthKind.PERSONAL) {
            return ctx.canAccessHost(hostId) && isOwned(ctx, hostId);
        }
        // OAuth (fondation) : postes possédés par l'utilisateur.
        return isOwned(ctx, hostId);
    }

    private boolean isOwned(McpCallContext ctx, UUID hostId) {
        try {
            RunnerHost host = hostService.requireOwned(ctx.user().id(), hostId);
            return host != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
