package fr.claudegateway.atelier;

import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;
import fr.claudegateway.user.UserRole;

/**
 * Contrôle d'accès à l'Atelier (F-28 / SF-28-06, amendé par F-40 / SF-40-01). L'Atelier est ouvert
 * aux utilisateurs de rôle {@code ADMIN} (bypass) <b>ou</b> à ceux qui ont le <b>droit d'Atelier</b>,
 * tel que défini par {@link SpaceEntitlementService} pour l'espace Forge (F-107 / SF-107-02) :
 * plan Gold actif, option Forge active sur un plan porteur actif, ou accès offert. Toute autre situation est refusée (fail-closed).
 *
 * <p>Ce service ne connaît plus les plans : il résout l'identité du contexte de sécurité, applique
 * le bypass administrateur et pose l'exception. La question « cet utilisateur a-t-il payé pour
 * l'Atelier ? » est une question de facturation et vit dans le paquet {@code billing}.</p>
 *
 * <p><b>Trois gardes</b> (F-107 / SF-107-07) — le runner est commun aux deux espaces (cadrage F-106 §3) :</p>
 * <ul>
 *   <li>{@link #requireAccess()} — la <b>Forge</b> : projets, terminaux de projet, terminal du poste,
 *       carte, gouvernance ;</li>
 *   <li>{@link #requireRunnerAccess()} — le <b>runner</b> : postes, appairage, statut, coupe-circuit,
 *       ouvert par le droit Forge <b>ou</b> le droit Vigie ;</li>
 *   <li>{@link #requireTerminalAccess(UUID)} — un <b>terminal désigné</b> : le terminal Teams suit la
 *       garde runner, tout autre workspace la garde Forge.</li>
 * </ul>
 *
 * <p>Le gating ne touche pas à l'isolation {@code user_id} (toujours appliquée en aval par les
 * services d'atelier).</p>
 */
@Service
public class AtelierAccessService {

    private final CurrentUser currentUser;
    private final SpaceEntitlementService entitlementService;
    /** Lecture du seul drapeau « terminal Teams » d'un workspace possédé (SF-107-07). */
    private final WorkspaceRepository workspaceRepository;

    public AtelierAccessService(CurrentUser currentUser, SpaceEntitlementService entitlementService,
            WorkspaceRepository workspaceRepository) {
        this.currentUser = currentUser;
        this.entitlementService = entitlementService;
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * Exige que l'utilisateur courant ait accès à l'Atelier.
     *
     * @throws AtelierAccessDeniedException si l'utilisateur n'est ni admin ni détenteur du droit
     */
    public void requireAccess() {
        AuthenticatedUser principal = currentUser.principal()
                .orElseThrow(AtelierAccessDeniedException::new);
        if (!isAllowed(principal)) {
            throw new AtelierAccessDeniedException();
        }
    }

    /**
     * Indique, sans lever d'exception, si l'utilisateur courant a accès à l'Atelier. Utile au
     * frontend (affichage de l'upsell), aux endpoints SSE (dont le refus voyage dans le flux) et
     * aux tests.
     *
     * @return {@code true} si l'utilisateur est admin ou détient le droit d'Atelier, {@code false} sinon
     */
    public boolean hasAccess() {
        return currentUser.principal().map(this::isAllowed).orElse(false);
    }

    /**
     * Exige le droit au <b>runner</b> (F-107 / SF-107-07) : Forge <b>ou</b> Vigie, administrateur compris.
     *
     * @throws AtelierAccessDeniedException si l'utilisateur n'a aucun des deux espaces
     */
    public void requireRunnerAccess() {
        if (!hasRunnerAccess()) {
            throw new AtelierAccessDeniedException();
        }
    }

    /** Le droit au runner de l'utilisateur courant, sans lever d'exception. */
    public boolean hasRunnerAccess() {
        return currentUser.principal().map(this::isRunnerAllowed).orElse(false);
    }

    /**
     * Exige le droit d'utiliser <b>ce</b> terminal (F-107 / SF-107-07) : le terminal Teams de
     * l'utilisateur courant s'ouvre avec le droit au runner ; tout autre workspace — projet, terminal du
     * poste, identifiant inconnu ou appartenant à autrui — exige la Forge. Un terminal Teams d'autrui
     * n'est donc jamais distingué d'un projet : le refus est le même.
     *
     * @param workspaceId workspace désigné par l'adresse
     * @throws AtelierAccessDeniedException si le droit manque
     */
    public void requireTerminalAccess(UUID workspaceId) {
        if (!hasTerminalAccess(workspaceId)) {
            throw new AtelierAccessDeniedException();
        }
    }

    /**
     * Le droit d'utiliser ce terminal, sans lever d'exception : c'est la forme des flux SSE, dont le
     * refus voyage dans le flux.
     */
    public boolean hasTerminalAccess(UUID workspaceId) {
        return currentUser.principal().map(principal -> {
            if (isAllowed(principal)) {
                return true; // La Forge ouvre tous les terminaux ; on ne lit pas le workspace.
            }
            return isOwnTeamsTerminal(principal, workspaceId) && isRunnerAllowed(principal);
        }).orElse(false);
    }

    private boolean isOwnTeamsTerminal(AuthenticatedUser principal, UUID workspaceId) {
        return workspaceId != null && workspaceRepository.findByIdAndUserId(workspaceId, principal.id())
                .map(Workspace::isTeamsTerminal)
                .orElse(false);
    }

    private boolean isRunnerAllowed(AuthenticatedUser principal) {
        return isAllowed(principal)
                || entitlementService.isEntitled(principal.id(), EntitlementSpace.VIGIE);
    }

    private boolean isAllowed(AuthenticatedUser principal) {
        if (principal.role() == UserRole.ADMIN) {
            return true; // Bypass administrateur : aucun abonnement n'est consulté.
        }
        return entitlementService.isEntitled(principal.id(), EntitlementSpace.FORGE);
    }
}
