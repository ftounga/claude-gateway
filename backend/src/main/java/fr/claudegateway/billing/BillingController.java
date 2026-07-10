package fr.claudegateway.billing;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.billing.dto.ChangePlanRequest;
import fr.claudegateway.billing.dto.CheckoutRequest;
import fr.claudegateway.billing.dto.CheckoutResponse;
import fr.claudegateway.billing.dto.PlanResponse;
import fr.claudegateway.billing.dto.PlansResponse;
import fr.claudegateway.billing.dto.SubscriptionResponse;
import fr.claudegateway.billing.dto.TopUpCheckoutRequest;
import fr.claudegateway.billing.dto.TopUpPacksResponse;
import fr.claudegateway.quota.QuotaProperties;
import jakarta.validation.Valid;

/**
 * Endpoints de billing côté utilisateur (F-09). L'identité provient exclusivement du
 * {@link CurrentUser} (JWT) : l'isolation {@code user_id} est appliquée dans les services, jamais
 * depuis un paramètre client. Aucune logique métier ici (CODING_RULES — controllers fins).
 */
@RestController
@RequestMapping("/billing")
public class BillingController {

    private final PlanCatalog planCatalog;
    private final SubscriptionService subscriptionService;
    private final CheckoutService checkoutService;
    private final TopUpCatalog topUpCatalog;
    private final TopUpService topUpService;
    private final CurrentUser currentUser;
    private final BillingProperties billingProperties;
    private final QuotaProperties quotaProperties;

    public BillingController(
            PlanCatalog planCatalog,
            SubscriptionService subscriptionService,
            CheckoutService checkoutService,
            TopUpCatalog topUpCatalog,
            TopUpService topUpService,
            CurrentUser currentUser,
            BillingProperties billingProperties,
            QuotaProperties quotaProperties) {
        this.planCatalog = planCatalog;
        this.subscriptionService = subscriptionService;
        this.checkoutService = checkoutService;
        this.topUpCatalog = topUpCatalog;
        this.topUpService = topUpService;
        this.currentUser = currentUser;
        this.billingProperties = billingProperties;
        this.quotaProperties = quotaProperties;
    }

    /**
     * Plans proposés à la souscription : uniquement ceux ayant un price Stripe configuré, enrichis du
     * quota mensuel et du montant d'affichage (SF-21-05). Le price ID Stripe reste interne.
     */
    @GetMapping("/plans")
    public PlansResponse plans() {
        List<PlanResponse> plans = planCatalog.plans().stream()
                .filter(plan -> org.springframework.util.StringUtils.hasText(
                        billingProperties.stripe().priceId(plan.code())))
                .map(plan -> PlanResponse.of(
                        plan,
                        quotaProperties.tokensForPlan(plan.code()),
                        billingProperties.stripe().displayPrice(plan.code())))
                .toList();
        return new PlansResponse(plans);
    }

    /** Abonnement de l'utilisateur courant (essai provisionné à la volée si absent). */
    @GetMapping("/subscription")
    public SubscriptionResponse subscription() {
        UUID userId = currentUser.requireId();
        return SubscriptionResponse.from(subscriptionService.getOrCreateForUser(userId));
    }

    /** Crée une session de paiement Stripe pour le plan demandé et renvoie l'URL de redirection. */
    @PostMapping("/checkout")
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request) {
        AuthenticatedUser user = currentUser.principal()
                .orElseThrow(() -> new IllegalStateException("Aucun utilisateur authentifié"));
        return CheckoutResponse.from(
                checkoutService.createCheckout(user.id(), user.email(), request.planCode()));
    }

    /**
     * Change le plan de l'abonnement existant (upgrade/downgrade, SF-21-05). 409 si aucun abonnement
     * actif (souscrire d'abord) ; 400 si plan inconnu.
     */
    @PostMapping("/subscription/change")
    public SubscriptionResponse changePlan(@Valid @RequestBody ChangePlanRequest request) {
        UUID userId = currentUser.requireId();
        return SubscriptionResponse.from(subscriptionService.changePlan(userId, request.planCode()));
    }

    /** Catalogue des packs de tokens rachetables (top-up, F-21). */
    @GetMapping("/topups")
    public TopUpPacksResponse topups() {
        return TopUpPacksResponse.from(topUpCatalog.packs());
    }

    /** Crée une session de paiement one-shot pour le rachat d'un pack de tokens (top-up, F-21). */
    @PostMapping("/topup/checkout")
    public CheckoutResponse topUpCheckout(@Valid @RequestBody TopUpCheckoutRequest request) {
        AuthenticatedUser user = currentUser.principal()
                .orElseThrow(() -> new IllegalStateException("Aucun utilisateur authentifié"));
        return CheckoutResponse.from(
                topUpService.createTopUpCheckout(user.id(), user.email(), request.packCode()));
    }
}
