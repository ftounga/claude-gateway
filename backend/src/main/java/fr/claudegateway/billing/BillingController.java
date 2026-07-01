package fr.claudegateway.billing;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.billing.dto.PlansResponse;
import fr.claudegateway.billing.dto.SubscriptionResponse;

/**
 * Endpoints de consultation du billing (F-09). L'identité provient exclusivement du
 * {@link CurrentUser} (JWT) : l'isolation {@code user_id} est appliquée dans le service, jamais
 * depuis un paramètre client. Aucune logique métier ici (CODING_RULES — controllers fins).
 */
@RestController
@RequestMapping("/billing")
public class BillingController {

    private final PlanCatalog planCatalog;
    private final SubscriptionService subscriptionService;
    private final CurrentUser currentUser;

    public BillingController(
            PlanCatalog planCatalog,
            SubscriptionService subscriptionService,
            CurrentUser currentUser) {
        this.planCatalog = planCatalog;
        this.subscriptionService = subscriptionService;
        this.currentUser = currentUser;
    }

    /** Catalogue des plans proposés (sans prix ni price ID Stripe). */
    @GetMapping("/plans")
    public PlansResponse plans() {
        return PlansResponse.from(planCatalog.plans());
    }

    /** Abonnement de l'utilisateur courant (essai provisionné à la volée si absent). */
    @GetMapping("/subscription")
    public SubscriptionResponse subscription() {
        UUID userId = currentUser.requireId();
        return SubscriptionResponse.from(subscriptionService.getOrCreateForUser(userId));
    }
}
