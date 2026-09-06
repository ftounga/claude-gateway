package fr.claudegateway.billing;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import fr.claudegateway.billing.provider.AtelierOptionCheckoutCommand;
import fr.claudegateway.billing.provider.BillingProvider;
import fr.claudegateway.billing.provider.BillingProviderUnavailableException;
import fr.claudegateway.billing.provider.CheckoutSession;

/**
 * Souscription et résiliation de l'<b>option Atelier</b> (F-40 / SF-40-02). L'option est un
 * abonnement mensuel <b>distinct</b> de celui du plan : elle ouvre le droit d'accès à l'Atelier
 * (règle portée par {@link AtelierEntitlementService}) <b>sans changer aucun quota</b>.
 *
 * <p>Toutes les opérations prennent le {@code userId} du contexte de sécurité — jamais un paramètre
 * client — et passent par {@link SubscriptionService#getOrCreateForUser(UUID)}, filtré sur
 * {@code user_id} unique.</p>
 */
@Service
public class AtelierOptionService {

    private static final Logger log = LoggerFactory.getLogger(AtelierOptionService.class);

    /** Statuts de plan sous lesquels une option peut être souscrite (mêmes que ceux du droit). */
    private static final Set<SubscriptionStatus> LIVE_STATUSES =
            EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE);

    /** Plans porteurs : l'option est un supplément, elle ne se vend pas seule (F-40, D2). */
    private static final Set<PlanCode> CARRIER_PLANS = EnumSet.of(PlanCode.SOLO, PlanCode.PRO);

    private final SubscriptionService subscriptionService;
    private final SubscriptionRepository subscriptionRepository;
    private final AtelierEntitlementService entitlementService;
    private final BillingProvider billingProvider;
    private final BillingProperties properties;

    public AtelierOptionService(
            SubscriptionService subscriptionService,
            SubscriptionRepository subscriptionRepository,
            AtelierEntitlementService entitlementService,
            BillingProvider billingProvider,
            BillingProperties properties) {
        this.subscriptionService = subscriptionService;
        this.subscriptionRepository = subscriptionRepository;
        this.entitlementService = entitlementService;
        this.billingProvider = billingProvider;
        this.properties = properties;
    }

    /**
     * État de l'option pour l'utilisateur : prix d'affichage (configuration), droit effectif, droit
     * déjà inclus au plan, statut de l'option et terme d'une résiliation programmée.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @return la description, jamais {@code null}
     */
    @Transactional
    public AtelierOptionView describe(UUID userId) {
        return view(subscriptionService.getOrCreateForUser(userId));
    }

    /**
     * Crée une session de paiement pour souscrire l'option.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @param email  email de l'utilisateur (pré-remplissage Checkout)
     * @return la session de paiement (URL de redirection)
     * @throws AtelierOptionIncludedInPlanException l'offre inclut déjà l'Atelier (Gold)
     * @throws NoActiveSubscriptionException        aucun plan porteur Solo/Pro actif
     * @throws AtelierOptionAlreadyActiveException  l'option est déjà en cours
     * @throws BillingProviderUnavailableException  fournisseur ou price ID d'option non configuré
     */
    @Transactional
    public CheckoutSession startCheckout(UUID userId, String email) {
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);

        if (entitlementService.isIncludedInPlan(subscription)) {
            throw new AtelierOptionIncludedInPlanException();
        }
        if (!isCarriedByLivePlan(subscription)) {
            throw new NoActiveSubscriptionException(
                    "Souscrivez une offre Solo ou Pro avant d'ajouter l'option Atelier.");
        }
        if (isOptionLive(subscription)) {
            throw new AtelierOptionAlreadyActiveException();
        }

        String priceId = properties.stripe().atelierOptionPriceId();
        if (!StringUtils.hasText(priceId)) {
            throw new BillingProviderUnavailableException(
                    "Aucun price configuré pour l'option Atelier.");
        }

        return billingProvider.createAtelierOptionCheckoutSession(new AtelierOptionCheckoutCommand(
                userId, email, subscription.getStripeCustomerId(), priceId));
    }

    /**
     * Programme la résiliation de l'option <b>en fin de période</b>. Le statut reste {@code ACTIVE}
     * et le droit reste ouvert jusqu'au terme : le mois est payé, il est dû jusqu'au bout. La
     * fermeture effective viendra du webhook de suppression d'abonnement.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @return l'état de l'option après programmation
     * @throws AtelierOptionNotActiveException aucune option en cours à résilier
     */
    @Transactional
    public AtelierOptionView cancel(UUID userId) {
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        if (!isOptionLive(subscription)
                || !StringUtils.hasText(subscription.getAtelierOptionStripeSubscriptionId())) {
            throw new AtelierOptionNotActiveException();
        }

        OffsetDateTime cancelAt = billingProvider.scheduleSubscriptionCancellation(
                subscription.getAtelierOptionStripeSubscriptionId());
        subscription.setAtelierOptionCancelAt(cancelAt);
        Subscription saved = subscriptionRepository.save(subscription);
        log.info("Résiliation d'option Atelier programmée pour l'utilisateur {}", userId);
        return view(saved);
    }

    private AtelierOptionView view(Subscription subscription) {
        return new AtelierOptionView(
                properties.stripe().atelierOptionDisplayPrice(),
                entitlementService.isEntitled(subscription),
                entitlementService.isIncludedInPlan(subscription),
                subscription.getAtelierOptionStatus(),
                subscription.getAtelierOptionCancelAt(),
                properties.stripe().isAtelierOptionConfigured());
    }

    private boolean isCarriedByLivePlan(Subscription subscription) {
        return CARRIER_PLANS.contains(subscription.getPlanCode())
                && subscription.getStatus() != null
                && LIVE_STATUSES.contains(subscription.getStatus());
    }

    private boolean isOptionLive(Subscription subscription) {
        SubscriptionStatus status = subscription.getAtelierOptionStatus();
        return status != null && LIVE_STATUSES.contains(status);
    }

    /**
     * État de l'option Atelier, indépendant du transport (le DTO REST s'en déduit).
     *
     * @param priceEur       montant d'affichage EUR issu de la configuration
     * @param entitled       droit d'Atelier effectif de l'utilisateur
     * @param includedInPlan le droit vient du plan lui-même (Gold) : l'option serait sans objet
     * @param optionStatus   statut de l'option, ou {@code null} si jamais souscrite
     * @param cancelAt       terme d'une résiliation programmée, ou {@code null}
     * @param available      l'option est réellement souscriptible (fournisseur + price configurés)
     */
    public record AtelierOptionView(
            String priceEur,
            boolean entitled,
            boolean includedInPlan,
            SubscriptionStatus optionStatus,
            OffsetDateTime cancelAt,
            boolean available) {
    }
}
