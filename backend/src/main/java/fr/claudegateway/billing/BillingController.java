package fr.claudegateway.billing;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.billing.dto.CheckoutRequest;
import fr.claudegateway.billing.dto.CheckoutResponse;
import fr.claudegateway.billing.dto.PlansResponse;
import fr.claudegateway.billing.dto.SubscriptionResponse;
import fr.claudegateway.billing.dto.TopUpCheckoutRequest;
import fr.claudegateway.billing.dto.TopUpPacksResponse;
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

    public BillingController(
            PlanCatalog planCatalog,
            SubscriptionService subscriptionService,
            CheckoutService checkoutService,
            TopUpCatalog topUpCatalog,
            TopUpService topUpService,
            CurrentUser currentUser) {
        this.planCatalog = planCatalog;
        this.subscriptionService = subscriptionService;
        this.checkoutService = checkoutService;
        this.topUpCatalog = topUpCatalog;
        this.topUpService = topUpService;
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

    /** Crée une session de paiement Stripe pour le plan demandé et renvoie l'URL de redirection. */
    @PostMapping("/checkout")
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request) {
        AuthenticatedUser user = currentUser.principal()
                .orElseThrow(() -> new IllegalStateException("Aucun utilisateur authentifié"));
        return CheckoutResponse.from(
                checkoutService.createCheckout(user.id(), user.email(), request.planCode()));
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
