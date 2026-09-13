package fr.claudegateway.teams;

import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;
import fr.claudegateway.user.UserRole;

/**
 * Contrôle d'accès au <b>volet Teams</b> (F-89 / SF-89-01). Jumeau exact d'{@code AtelierAccessService} :
 * il résout l'identité du contexte de sécurité, applique le <b>bypass administrateur</b> et pose
 * l'exception ; la question « ce compte a-t-il payé ? » vit dans {@link SpaceEntitlementService} (espace Vigie),
 * paquet {@code billing}.
 *
 * <p><b>Deux façons de poser la question, et elles ne servent pas au même endroit :</b></p>
 * <ul>
 *   <li>{@link #requireAccess()} — l'<b>ouverture</b> du terminal Teams. Sans le droit, le terminal
 *       n'existe pas : on refuse, en 403.</li>
 *   <li>{@link #hasAccess(UUID)} — la <b>construction du catalogue d'outils</b>. Là, aucune
 *       exception : sans le droit, les outils {@code teams_*} ne sont simplement pas donnés à
 *       l'agent. C'est toute la nuance du cadrage — <i>l'agent ne refuse pas, il n'a pas la
 *       capacité</i> — et elle se lit ici, dans la signature de la méthode.</li>
 * </ul>
 *
 * <p>Ce contrôle ne touche pas à l'isolation {@code user_id}, toujours appliquée en aval.</p>
 */
@Service
public class TeamsAccessService {

    private final CurrentUser currentUser;
    private final SpaceEntitlementService entitlementService;

    public TeamsAccessService(CurrentUser currentUser, SpaceEntitlementService entitlementService) {
        this.currentUser = currentUser;
        this.entitlementService = entitlementService;
    }

    /**
     * Exige que l'utilisateur courant ait le droit Teams.
     *
     * @throws TeamsAccessDeniedException si l'utilisateur n'est ni admin ni détenteur du droit
     */
    public void requireAccess() {
        AuthenticatedUser principal = currentUser.principal()
                .orElseThrow(TeamsAccessDeniedException::new);
        if (!isAllowed(principal)) {
            throw new TeamsAccessDeniedException();
        }
    }

    /** Le droit de l'utilisateur courant, sans lever d'exception (écran, flux SSE, tests). */
    public boolean hasAccess() {
        return currentUser.principal().map(this::isAllowed).orElse(false);
    }

    /**
     * Le droit d'un utilisateur <b>désigné</b>, sans lever d'exception.
     *
     * <p>C'est la forme qu'appelle la construction du catalogue d'outils : elle tourne dans un tour
     * d'agent, parfois hors du fil de la requête HTTP (flux SSE, relance du runner), où le contexte
     * de sécurité n'est pas toujours celui qu'on croit. Prendre le {@code userId} <b>du tour</b> —
     * celui-là même dont on a lu le workspace — est la seule façon de garder le bon compte.</p>
     *
     * <p>Le <b>bypass administrateur</b> ne s'applique que si le principal courant <i>est</i> cet
     * utilisateur : sans quoi un administrateur ouvrirait un terminal Teams (bypass de
     * {@link #requireAccess()}) dont l'agent n'aurait, lui, aucun outil. Pour tout autre cas —
     * principal absent, ou principal différent — on retombe sur le droit du compte, fail-closed.</p>
     *
     * @param userId propriétaire du terminal dont on construit le catalogue
     * @return {@code true} si les outils {@code teams_*} doivent être donnés à l'agent
     */
    public boolean hasAccess(UUID userId) {
        if (userId == null) {
            return false;
        }
        return currentUser.principal()
                .filter(principal -> userId.equals(principal.id()))
                .map(this::isAllowed)
                .orElseGet(() -> entitlementService.isEntitled(userId, EntitlementSpace.VIGIE));
    }

    private boolean isAllowed(AuthenticatedUser principal) {
        if (principal.role() == UserRole.ADMIN) {
            return true; // Bypass administrateur : aucun abonnement n'est consulté.
        }
        return entitlementService.isEntitled(principal.id(), EntitlementSpace.VIGIE);
    }
}
