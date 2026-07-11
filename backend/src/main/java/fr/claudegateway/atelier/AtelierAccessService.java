package fr.claudegateway.atelier;

import org.springframework.stereotype.Service;

import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionService;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.user.UserRole;

/**
 * Contrôle d'accès à l'Atelier (F-28 / SF-28-06) : l'Atelier est réservé aux utilisateurs de rôle
 * {@code ADMIN} (bypass) <b>ou</b> abonnés à l'offre {@link PlanCode#GOLD} active
 * ({@code ACTIVE}/{@code PAST_DUE}). Toute autre situation (SOLO/PRO/essai, GOLD annulé/incomplet)
 * est refusée (fail-closed, cohérent avec {@code EntitlementService}).
 *
 * <p>Le gating est purement lié à l'identité du contexte de sécurité et à l'abonnement de
 * l'utilisateur ; il ne touche pas à l'isolation {@code user_id} (toujours appliquée en aval par
 * les services d'atelier).</p>
 */
@Service
public class AtelierAccessService {

    private final CurrentUser currentUser;
    private final SubscriptionService subscriptionService;

    public AtelierAccessService(CurrentUser currentUser, SubscriptionService subscriptionService) {
        this.currentUser = currentUser;
        this.subscriptionService = subscriptionService;
    }

    /**
     * Exige que l'utilisateur courant ait accès à l'Atelier.
     *
     * @throws AtelierAccessDeniedException si l'utilisateur n'est ni admin ni abonné Gold actif
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
     * frontend (affichage de l'upsell) et aux tests.
     *
     * @return {@code true} si l'utilisateur est admin ou abonné Gold actif, {@code false} sinon
     */
    public boolean hasAccess() {
        return currentUser.principal().map(this::isAllowed).orElse(false);
    }

    private boolean isAllowed(AuthenticatedUser principal) {
        if (principal.role() == UserRole.ADMIN) {
            return true; // Bypass administrateur.
        }
        Subscription subscription = subscriptionService.getOrCreateForUser(principal.id());
        SubscriptionStatus status = subscription.getStatus();
        return subscription.getPlanCode() == PlanCode.GOLD
                && (status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.PAST_DUE);
    }
}
