package fr.claudegateway.atelier;

import org.springframework.stereotype.Service;

import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.billing.AtelierEntitlementService;
import fr.claudegateway.user.UserRole;

/**
 * Contrôle d'accès à l'Atelier (F-28 / SF-28-06, amendé par F-40 / SF-40-01). L'Atelier est ouvert
 * aux utilisateurs de rôle {@code ADMIN} (bypass) <b>ou</b> à ceux qui ont le <b>droit d'Atelier</b>,
 * tel que défini par {@link AtelierEntitlementService} : plan Gold actif, ou option Atelier active
 * sur un plan Solo/Pro actif. Toute autre situation est refusée (fail-closed).
 *
 * <p>Ce service ne connaît plus les plans : il résout l'identité du contexte de sécurité, applique
 * le bypass administrateur et pose l'exception. La question « cet utilisateur a-t-il payé pour
 * l'Atelier ? » est une question de facturation et vit dans le paquet {@code billing}.</p>
 *
 * <p>Le gating ne touche pas à l'isolation {@code user_id} (toujours appliquée en aval par les
 * services d'atelier).</p>
 */
@Service
public class AtelierAccessService {

    private final CurrentUser currentUser;
    private final AtelierEntitlementService entitlementService;

    public AtelierAccessService(CurrentUser currentUser, AtelierEntitlementService entitlementService) {
        this.currentUser = currentUser;
        this.entitlementService = entitlementService;
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

    private boolean isAllowed(AuthenticatedUser principal) {
        if (principal.role() == UserRole.ADMIN) {
            return true; // Bypass administrateur : aucun abonnement n'est consulté.
        }
        return entitlementService.isEntitled(principal.id());
    }
}
