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

import fr.claudegateway.billing.provider.BillingProvider;
import fr.claudegateway.billing.provider.BillingProviderUnavailableException;
import fr.claudegateway.billing.provider.CheckoutSession;
import fr.claudegateway.billing.provider.VigieOptionCheckoutCommand;

/**
 * Souscription et résiliation de l'<b>option Vigie</b> (F-107 / SF-107-03) : Teams, Radar et réunions,
 * en supplément d'un plan Solo, Pro, BYOK ou Gold Forge. Miroir exact de {@link AtelierOptionService} :
 * un abonnement mensuel <b>distinct</b> de celui du plan, qui ouvre le droit à l'espace
 * {@link EntitlementSpace#VIGIE} (règle portée par {@link SpaceEntitlementService}) <b>sans changer aucun
 * quota</b> de conversation.
 *
 * <p>Toutes les opérations prennent le {@code userId} du contexte de sécurité — jamais un paramètre
 * client — et passent par {@link SubscriptionService#getOrCreateForUser(UUID)}, filtré sur
 * {@code user_id} unique.</p>
 */
@Service
public class VigieOptionService {

    private static final Logger log = LoggerFactory.getLogger(VigieOptionService.class);

    private static final Set<SubscriptionStatus> LIVE_STATUSES =
            EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE);

    private final SubscriptionService subscriptionService;
    private final SubscriptionRepository subscriptionRepository;
    private final SpaceEntitlementService entitlementService;
    private final BillingProvider billingProvider;
    private final BillingProperties properties;

    public VigieOptionService(
            SubscriptionService subscriptionService,
            SubscriptionRepository subscriptionRepository,
            SpaceEntitlementService entitlementService,
            BillingProvider billingProvider,
            BillingProperties properties) {
        this.subscriptionService = subscriptionService;
        this.subscriptionRepository = subscriptionRepository;
        this.entitlementService = entitlementService;
        this.billingProvider = billingProvider;
        this.properties = properties;
    }

    /**
     * État de l'option pour l'utilisateur.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @return la description, jamais {@code null}
     */
    @Transactional
    public VigieOptionView describe(UUID userId) {
        return view(subscriptionService.getOrCreateForUser(userId));
    }

    /**
     * Crée une session de paiement pour souscrire l'option Vigie.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @param email  email de l'utilisateur (pré-remplissage Checkout)
     * @return la session de paiement
     * @throws VigieOptionIncludedInPlanException  l'offre inclut déjà la Vigie (Gold Vigie, Gold complet)
     * @throws NoActiveSubscriptionException       aucun plan porteur en cours
     * @throws VigieOptionAlreadyActiveException   l'option est déjà en cours
     * @throws BillingProviderUnavailableException fournisseur ou price ID d'option non configuré
     */
    @Transactional
    public CheckoutSession startCheckout(UUID userId, String email) {
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);

        if (entitlementService.isIncludedInPlan(subscription, EntitlementSpace.VIGIE)) {
            throw new VigieOptionIncludedInPlanException();
        }
        if (!isCarriedByLivePlan(subscription)) {
            throw new NoActiveSubscriptionException(
                    "Souscrivez une offre Solo, Pro, BYOK ou Gold Forge avant d'ajouter l'option Vigie.");
        }
        if (isOptionLive(subscription)) {
            throw new VigieOptionAlreadyActiveException();
        }

        String priceId = properties.stripe().vigieOptionPriceId();
        if (!StringUtils.hasText(priceId)) {
            throw new BillingProviderUnavailableException("Aucun price configuré pour l'option Vigie.");
        }

        return billingProvider.createVigieOptionCheckoutSession(new VigieOptionCheckoutCommand(
                userId, email, subscription.getStripeCustomerId(), priceId));
    }

    /**
     * Programme la résiliation de l'option <b>en fin de période</b> : le droit reste ouvert jusqu'au terme
     * payé ; la fermeture effective viendra du webhook de suppression d'abonnement.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @return l'état de l'option après programmation
     * @throws VigieOptionNotActiveException aucune option en cours à résilier
     */
    @Transactional
    public VigieOptionView cancel(UUID userId) {
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        if (!isOptionLive(subscription)
                || !StringUtils.hasText(subscription.getVigieOptionStripeSubscriptionId())) {
            throw new VigieOptionNotActiveException();
        }

        OffsetDateTime cancelAt = billingProvider.scheduleSubscriptionCancellation(
                subscription.getVigieOptionStripeSubscriptionId());
        subscription.setVigieOptionCancelAt(cancelAt);
        Subscription saved = subscriptionRepository.save(subscription);
        log.info("Résiliation d'option Vigie programmée pour l'utilisateur {}", userId);
        return view(saved);
    }

    private VigieOptionView view(Subscription subscription) {
        return new VigieOptionView(
                properties.stripe().vigieOptionDisplayPrice(),
                entitlementService.isEntitled(subscription, EntitlementSpace.VIGIE),
                entitlementService.isIncludedInPlan(subscription, EntitlementSpace.VIGIE),
                subscription.getTeamsOptionStatus(),
                subscription.getVigieOptionCancelAt(),
                properties.stripe().isVigieOptionConfigured(),
                entitlementService.isGrantedByRole(subscription.getUserId()),
                subscription.getPlanCode() == PlanCode.GOLD);
    }

    private boolean isCarriedByLivePlan(Subscription subscription) {
        return entitlementService.isOptionCarrier(subscription.getPlanCode(), EntitlementSpace.VIGIE)
                && subscription.getStatus() != null
                && LIVE_STATUSES.contains(subscription.getStatus());
    }

    private static boolean isOptionLive(Subscription subscription) {
        SubscriptionStatus status = subscription.getTeamsOptionStatus();
        return status != null && LIVE_STATUSES.contains(status);
    }

    /**
     * État de l'option Vigie, indépendant du transport.
     *
     * @param priceEur                 montant d'affichage EUR issu de la configuration (69 par défaut)
     * @param entitled                 droit Vigie effectif, quelle qu'en soit la source
     * @param includedInPlan           le droit vient du plan (Gold Vigie, Gold complet) : option sans objet
     * @param optionStatus             statut de l'option, ou {@code null} si jamais souscrite
     * @param cancelAt                 terme d'une résiliation programmée, ou {@code null}
     * @param available                l'option est réellement souscriptible (fournisseur + price configurés)
     * @param includedForAdministrator le droit vient du rôle administrateur (SF-107-06)
     * @param goldCarrier              le plan porteur est Gold Forge : l'écran signale que Gold complet
     *                                 revient moins cher que Gold Forge + option
     */
    public record VigieOptionView(
            String priceEur,
            boolean entitled,
            boolean includedInPlan,
            SubscriptionStatus optionStatus,
            OffsetDateTime cancelAt,
            boolean available,
            boolean includedForAdministrator,
            boolean goldCarrier) {
    }
}
