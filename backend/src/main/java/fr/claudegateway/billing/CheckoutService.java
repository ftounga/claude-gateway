package fr.claudegateway.billing;

import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.billing.provider.BillingProvider;
import fr.claudegateway.billing.provider.CheckoutCommand;
import fr.claudegateway.billing.provider.CheckoutSession;

/**
 * Orchestration de la souscription à un plan (F-09 / SF-09-02). Résout le plan depuis le catalogue,
 * s'assure que l'utilisateur a un abonnement (réutilise l'essai éventuel pour récupérer son client
 * Stripe existant), résout le price ID de configuration et délègue la création de la session au
 * {@link BillingProvider} — jamais à Stripe en direct.
 */
@Service
public class CheckoutService {

    private final PlanCatalog planCatalog;
    private final SubscriptionService subscriptionService;
    private final BillingProvider billingProvider;
    private final BillingProperties properties;

    public CheckoutService(
            PlanCatalog planCatalog,
            SubscriptionService subscriptionService,
            BillingProvider billingProvider,
            BillingProperties properties) {
        this.planCatalog = planCatalog;
        this.subscriptionService = subscriptionService;
        this.billingProvider = billingProvider;
        this.properties = properties;
    }

    /**
     * Crée une session de paiement pour le plan demandé et l'utilisateur courant.
     *
     * @param userId       utilisateur authentifié (contexte de sécurité)
     * @param email        email de l'utilisateur (pré-remplissage Checkout)
     * @param planCodeRaw  code de plan fourni par le client
     * @return la session de paiement (URL de redirection)
     * @throws UnknownPlanException code de plan absent/inconnu du catalogue
     */
    public CheckoutSession createCheckout(UUID userId, String email, String planCodeRaw) {
        Plan plan = resolvePlan(planCodeRaw);
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        String priceId = properties.stripe().priceId(plan.code());

        CheckoutCommand command = new CheckoutCommand(
                userId, email, subscription.getStripeCustomerId(), plan, priceId);
        return billingProvider.createCheckoutSession(command);
    }

    private Plan resolvePlan(String planCodeRaw) {
        PlanCode code = parse(planCodeRaw);
        return planCatalog.plans().stream()
                .filter(p -> p.code() == code)
                .findFirst()
                .orElseThrow(() -> new UnknownPlanException("Plan inconnu : " + planCodeRaw));
    }

    private static PlanCode parse(String planCodeRaw) {
        if (planCodeRaw == null || planCodeRaw.isBlank()) {
            throw new UnknownPlanException("Plan non fourni.");
        }
        try {
            return PlanCode.valueOf(planCodeRaw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new UnknownPlanException("Plan inconnu : " + planCodeRaw);
        }
    }
}
