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
    private final BillingPeriodSelection periodSelection;

    public CheckoutService(
            PlanCatalog planCatalog,
            SubscriptionService subscriptionService,
            BillingProvider billingProvider,
            BillingPeriodSelection periodSelection) {
        this.planCatalog = planCatalog;
        this.subscriptionService = subscriptionService;
        this.billingProvider = billingProvider;
        this.periodSelection = periodSelection;
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
        return createCheckout(userId, email, planCodeRaw, null);
    }

    /**
     * Crée une session de paiement pour le plan et la <b>périodicité</b> demandés (F-43).
     *
     * <p>La périodicité est résolue et validée <b>avant</b> tout appel au fournisseur : demander
     * l'annuel sur un plan qui n'en propose pas est refusé, jamais replié en silence vers le price
     * mensuel — le client cliquerait « à l'année » et serait débité au mois.</p>
     *
     * @param userId       utilisateur authentifié (contexte de sécurité)
     * @param email        email de l'utilisateur (pré-remplissage Checkout)
     * @param planCodeRaw  code de plan fourni par le client
     * @param periodRaw    périodicité demandée ({@code MONTHLY} / {@code YEARLY}) ; absente ⇒ mensuel
     * @return la session de paiement (URL de redirection)
     * @throws UnknownPlanException              code de plan absent/inconnu du catalogue
     * @throws UnknownBillingPeriodException     périodicité inconnue ou non achetable
     * @throws YearlyBillingUnavailableException annuel demandé sur un plan qui n'en propose pas
     */
    public CheckoutSession createCheckout(UUID userId, String email, String planCodeRaw, String periodRaw) {
        Plan plan = resolvePlan(planCodeRaw);
        BillingPeriod period = periodSelection.resolveFor(plan, periodRaw);
        String priceId = periodSelection.priceId(plan, period);

        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        CheckoutCommand command = new CheckoutCommand(
                userId, email, subscription.getStripeCustomerId(), plan, priceId, period);
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
