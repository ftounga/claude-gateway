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
import fr.claudegateway.billing.dto.AtelierOptionResponse;
import fr.claudegateway.billing.dto.ChangePlanRequest;
import fr.claudegateway.billing.dto.CheckoutRequest;
import fr.claudegateway.billing.dto.CheckoutResponse;
import fr.claudegateway.billing.dto.PlanResponse;
import fr.claudegateway.billing.dto.PlansResponse;
import fr.claudegateway.billing.dto.SeatsResponse;
import fr.claudegateway.billing.dto.SubscriptionResponse;
import fr.claudegateway.billing.dto.TopUpCheckoutRequest;
import fr.claudegateway.billing.dto.TopUpPackResponse;
import fr.claudegateway.billing.dto.TopUpPacksResponse;
import fr.claudegateway.billing.dto.VigieOptionResponse;
import fr.claudegateway.billing.seat.SeatQuotaService;
import fr.claudegateway.quota.EntitlementService;
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
    private final AtelierOptionService atelierOptionService;
    private final VigieOptionService vigieOptionService;
    private final SeatQuotaService seatQuotaService;
    private final CurrentUser currentUser;
    private final BillingProperties billingProperties;
    private final QuotaProperties quotaProperties;
    private final EntitlementService entitlementService;

    public BillingController(
            PlanCatalog planCatalog,
            SubscriptionService subscriptionService,
            CheckoutService checkoutService,
            TopUpCatalog topUpCatalog,
            TopUpService topUpService,
            AtelierOptionService atelierOptionService,
            VigieOptionService vigieOptionService,
            SeatQuotaService seatQuotaService,
            CurrentUser currentUser,
            BillingProperties billingProperties,
            QuotaProperties quotaProperties,
            EntitlementService entitlementService) {
        this.planCatalog = planCatalog;
        this.subscriptionService = subscriptionService;
        this.checkoutService = checkoutService;
        this.topUpCatalog = topUpCatalog;
        this.topUpService = topUpService;
        this.atelierOptionService = atelierOptionService;
        this.vigieOptionService = vigieOptionService;
        this.seatQuotaService = seatQuotaService;
        this.currentUser = currentUser;
        this.billingProperties = billingProperties;
        this.quotaProperties = quotaProperties;
        this.entitlementService = entitlementService;
    }

    /**
     * Plans proposés à la souscription : uniquement ceux ayant un price Stripe configuré, enrichis du
     * quota mensuel, du montant d'affichage (SF-21-05) et de l'engagement annuel quand il est
     * proposé (F-43). Le price ID Stripe reste interne.
     *
     * <p>Le quota exposé reste l'allocation <b>mensuelle</b> du plan, y compris pour un plan
     * proposé à l'année : {@code tokensForPlan} ne connaît que le {@link PlanCode}, jamais la
     * périodicité. C'est délibéré — l'engagement est annuel, l'allocation reste mensuelle.</p>
     */
    @GetMapping("/plans")
    public PlansResponse plans() {
        BillingProperties.Stripe stripe = billingProperties.stripe();
        List<PlanResponse> plans = planCatalog.plans().stream()
                .filter(plan -> org.springframework.util.StringUtils.hasText(stripe.priceId(plan.code())))
                .map(plan -> PlanResponse.of(
                        plan,
                        quotaProperties.tokensForPlan(plan.code()),
                        stripe.displayPrice(plan.code()),
                        stripe.yearlyDisplayPrice(plan.code()),
                        stripe.isYearlyAvailable(plan)))
                .toList();
        return new PlansResponse(plans);
    }

    /**
     * Postes comptés pour la période courante (F-65 / SF-65-01) : combien, lesquels, ce que les
     * suppléments apportent en jetons, et si le supplément est réellement facturé.
     *
     * <p>Lecture seule, isolation {@code user_id} : un poste d'un autre compte n'y apparaît jamais.
     * Aucun appel au fournisseur de paiement n'est émis par ce chemin — F-65 <b>compte</b>, il ne
     * facture pas lui-même.</p>
     */
    @GetMapping("/seats")
    public SeatsResponse seats(@org.springframework.web.bind.annotation.RequestParam(required = false) String space) {
        UUID userId = currentUser.requireId();
        // F-107 / SF-107-05 : le supplément est par espace ; en BYOK, aucun jeton plateforme n'est apporté.
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        boolean tokensApply = !entitlementService.isCustomerKeyBilled(subscription);
        return SeatsResponse.from(seatQuotaService.describe(userId, parseSpace(space), tokensApply));
    }

    /** Espace demandé ; absent ⇒ Forge ; inconnu ⇒ 400 (même erreur que les espaces d'un poste). */
    private static EntitlementSpace parseSpace(String raw) {
        if (raw == null || raw.isBlank()) {
            return EntitlementSpace.FORGE;
        }
        try {
            return EntitlementSpace.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new fr.claudegateway.runner.host.InvalidClientSpaceException(raw);
        }
    }

    /** Abonnement de l'utilisateur courant (essai provisionné à la volée si absent). */
    @GetMapping("/subscription")
    public SubscriptionResponse subscription() {
        UUID userId = currentUser.requireId();
        return describe(subscriptionService.getOrCreateForUser(userId));
    }

    /**
     * Crée une session de paiement Stripe pour le plan et la périodicité demandés, et renvoie l'URL
     * de redirection. La périodicité est optionnelle (F-43) : absente, elle vaut {@code MONTHLY}.
     */
    @PostMapping("/checkout")
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request) {
        AuthenticatedUser user = currentUser.principal()
                .orElseThrow(() -> new IllegalStateException("Aucun utilisateur authentifié"));
        return CheckoutResponse.from(checkoutService.createCheckout(
                user.id(), user.email(), request.planCode(), request.period()));
    }

    /**
     * Change le plan et/ou la <b>périodicité</b> de l'abonnement existant (upgrade/downgrade,
     * SF-21-05 ; mensuel ↔ annuel, F-43). 409 si aucun abonnement actif (souscrire d'abord) ou si
     * l'annuel est demandé sur un plan qui n'en propose pas ; 400 si plan ou périodicité inconnus.
     */
    @PostMapping("/subscription/change")
    public SubscriptionResponse changePlan(@Valid @RequestBody ChangePlanRequest request) {
        UUID userId = currentUser.requireId();
        return describe(subscriptionService.changePlan(userId, request.planCode(), request.period()));
    }

    /**
     * Projette un abonnement en réponse d'API, en y joignant le fait que ses appels sont servis par
     * la clé du client (F-41). C'est le <b>serveur</b> qui tranche : l'écran n'a jamais à déduire
     * l'offre d'un code de plan.
     */
    private SubscriptionResponse describe(Subscription subscription) {
        return SubscriptionResponse.from(
                subscription, entitlementService.isCustomerKeyBilled(subscription),
                billingProperties.trialDays(), quotaProperties.trialTokens());
    }

    /**
     * Catalogue des packs de tokens rachetables (top-up, F-21), enrichi depuis F-67 du
     * <b>montant d'affichage</b> de chaque pack.
     *
     * <p>Le montant vient de la configuration ({@code app.billing.stripe.topup-display-prices}),
     * jamais du code : c'est le même patron que les plans, et pour la même raison — un prix est une
     * décision commerciale, réversible sans redéploiement (OQ-07). Un pack dont le montant n'est pas
     * configuré est renvoyé avec {@code priceEur = null} et <b>reste listé</b> : il est vendable, son
     * price ID existe, et c'est à l'écran de dire que le prix sera indiqué au paiement.</p>
     *
     * <p>Le price ID Stripe, lui, ne sort pas d'ici — exactement comme pour les plans.</p>
     */
    @GetMapping("/topups")
    public TopUpPacksResponse topups() {
        BillingProperties.Stripe stripe = billingProperties.stripe();
        List<TopUpPackResponse> packs = topUpCatalog.packs().stream()
                .map(pack -> TopUpPackResponse.of(pack, stripe.topupDisplayPrice(pack.code())))
                .toList();
        return new TopUpPacksResponse(packs);
    }

    /** Crée une session de paiement one-shot pour le rachat d'un pack de tokens (top-up, F-21). */
    @PostMapping("/topup/checkout")
    public CheckoutResponse topUpCheckout(@Valid @RequestBody TopUpCheckoutRequest request) {
        AuthenticatedUser user = currentUser.principal()
                .orElseThrow(() -> new IllegalStateException("Aucun utilisateur authentifié"));
        return CheckoutResponse.from(
                topUpService.createTopUpCheckout(user.id(), user.email(), request.packCode()));
    }

    /**
     * État de l'<b>option Atelier</b> (F-40) : prix d'affichage, droit effectif, droit déjà inclus à
     * l'offre (Gold), statut de l'option et terme d'une résiliation programmée.
     */
    @GetMapping("/atelier-option")
    public AtelierOptionResponse atelierOption() {
        return AtelierOptionResponse.from(atelierOptionService.describe(currentUser.requireId()));
    }

    /**
     * Souscrit l'option Atelier : crée la session de paiement de l'abonnement <b>supplémentaire</b>
     * et renvoie l'URL de redirection. 409 si l'Atelier est déjà inclus à l'offre, si aucun plan
     * porteur Solo/Pro n'est actif, ou si l'option l'est déjà ; 503 si le paiement n'est pas configuré.
     */
    @PostMapping("/atelier-option/checkout")
    public CheckoutResponse atelierOptionCheckout() {
        AuthenticatedUser user = currentUser.principal()
                .orElseThrow(() -> new IllegalStateException("Aucun utilisateur authentifié"));
        return CheckoutResponse.from(atelierOptionService.startCheckout(user.id(), user.email()));
    }

    /**
     * Résilie l'option Atelier <b>en fin de période</b> : le droit reste ouvert jusqu'au terme déjà
     * payé. 409 si aucune option n'est en cours.
     */
    @PostMapping("/atelier-option/cancel")
    public AtelierOptionResponse cancelAtelierOption() {
        return AtelierOptionResponse.from(atelierOptionService.cancel(currentUser.requireId()));
    }

    /**
     * État de l'<b>option Vigie</b> (F-107 / SF-107-03) : prix d'affichage, droit effectif, droit inclus à
     * l'offre (Gold Vigie, Gold complet), statut et terme d'une résiliation programmée.
     */
    @GetMapping("/vigie-option")
    public VigieOptionResponse vigieOption() {
        return VigieOptionResponse.from(vigieOptionService.describe(currentUser.requireId()));
    }

    /**
     * Souscrit l'option Vigie : session de paiement d'un abonnement <b>supplémentaire</b>. 409 si la Vigie
     * est incluse, sans plan porteur en cours ou déjà active ; 503 si le paiement n'est pas configuré.
     */
    @PostMapping("/vigie-option/checkout")
    public CheckoutResponse vigieOptionCheckout() {
        AuthenticatedUser user = currentUser.principal()
                .orElseThrow(() -> new IllegalStateException("Aucun utilisateur authentifié"));
        return CheckoutResponse.from(vigieOptionService.startCheckout(user.id(), user.email()));
    }

    /** Résilie l'option Vigie en fin de période. 409 si aucune option n'est en cours. */
    @PostMapping("/vigie-option/cancel")
    public VigieOptionResponse cancelVigieOption() {
        return VigieOptionResponse.from(vigieOptionService.cancel(currentUser.requireId()));
    }
}
