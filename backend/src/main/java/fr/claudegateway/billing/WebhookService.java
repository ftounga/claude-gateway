package fr.claudegateway.billing;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import fr.claudegateway.billing.provider.BillingEvent;
import fr.claudegateway.billing.provider.BillingProvider;
import fr.claudegateway.quota.QuotaService;

/**
 * Applique les événements de facturation à l'état des abonnements (F-09 / SF-09-02). Reçoit un
 * {@link BillingEvent} déjà vérifié et normalisé par le {@link BillingProvider} (le service ne
 * connaît pas Stripe). Toutes les mutations sont idempotentes et ciblent l'unique abonnement de
 * l'utilisateur concerné (isolation par {@code userId}/{@code stripe_*_id}).
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final BillingProvider billingProvider;
    private final SubscriptionRepository subscriptionRepository;
    private final QuotaService quotaService;
    private final TopUpCatalog topUpCatalog;
    private final ProcessedBillingEventRepository processedEventRepository;

    public WebhookService(
            BillingProvider billingProvider,
            SubscriptionRepository subscriptionRepository,
            QuotaService quotaService,
            TopUpCatalog topUpCatalog,
            ProcessedBillingEventRepository processedEventRepository) {
        this.billingProvider = billingProvider;
        this.subscriptionRepository = subscriptionRepository;
        this.quotaService = quotaService;
        this.topUpCatalog = topUpCatalog;
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * Vérifie la signature, traduit puis applique l'événement. Un type non géré, ou un abonnement
     * introuvable, est ignoré silencieusement (le webhook répond tout de même 200).
     */
    @Transactional
    public void handle(String payload, String signatureHeader) {
        BillingEvent event = billingProvider.parseWebhookEvent(payload, signatureHeader);
        switch (event.type()) {
            case CHECKOUT_COMPLETED -> applyCheckoutCompleted(event);
            case TOPUP_COMPLETED -> applyTopUpCompleted(event);
            case ATELIER_OPTION_COMPLETED ->
                    applyAtelierOptionState(event, SubscriptionStatus.ACTIVE, true);
            case ATELIER_OPTION_UPDATED ->
                    applyAtelierOptionState(event, SubscriptionStatus.fromStripe(event.status()), false);
            case ATELIER_OPTION_DELETED ->
                    applyAtelierOptionState(event, SubscriptionStatus.CANCELED, true);
            case SUBSCRIPTION_UPDATED -> applySubscriptionUpdate(event);
            case SUBSCRIPTION_DELETED -> applySubscriptionDeleted(event);
            case UNHANDLED -> log.debug("Événement de facturation non géré, ignoré");
        }
    }

    /**
     * Crédite les tokens rachetés (top-up, F-21) sur la période courante de l'utilisateur, de façon
     * <b>idempotente</b> : le crédit n'est appliqué qu'une seule fois par événement fournisseur
     * ({@code event_id}), même si le webhook est rejoué. Le montant de tokens est autoritatif côté
     * serveur (résolu depuis le catalogue via {@code topupCode}), jamais fourni par le payload de montant.
     * Le marqueur d'idempotence et le crédit vivent dans la même transaction (rollback conjoint).
     */
    private void applyTopUpCompleted(BillingEvent event) {
        if (event.userId() == null) {
            log.warn("Rachat de tokens sans utilisateur identifié : ignoré");
            return;
        }
        if (!StringUtils.hasText(event.eventId())) {
            // Sans identifiant d'événement, on ne peut pas garantir l'idempotence : on s'abstient.
            log.warn("Rachat de tokens sans identifiant d'événement : ignoré");
            return;
        }
        Optional<TopUpPack> pack = topUpCatalog.find(event.topupCode());
        if (pack.isEmpty()) {
            log.warn("Rachat de tokens avec pack inconnu : ignoré");
            return;
        }
        // Rejeu séquentiel (cas courant) : déjà traité → on ne recrédite pas.
        if (processedEventRepository.existsById(event.eventId())) {
            log.debug("Rachat de tokens déjà traité (rejeu) : ignoré");
            return;
        }
        try {
            // Gate d'insertion (contrainte PK) contre un rejeu concurrent du même événement.
            processedEventRepository.saveAndFlush(
                    new ProcessedBillingEvent(event.eventId(), OffsetDateTime.now()));
        } catch (DataIntegrityViolationException alreadyProcessed) {
            log.debug("Rachat de tokens déjà traité (course concurrente) : ignoré");
            return;
        }
        quotaService.creditBonusTokens(event.userId(), pack.get().tokens());
    }

    private void applyCheckoutCompleted(BillingEvent event) {
        Optional<Subscription> found = resolve(event);
        if (found.isEmpty()) {
            log.warn("checkout.session.completed sans abonnement correspondant : ignoré");
            return;
        }
        Subscription subscription = found.get();
        if (StringUtils.hasText(event.stripeCustomerId())) {
            subscription.setStripeCustomerId(event.stripeCustomerId());
        }
        if (StringUtils.hasText(event.stripeSubscriptionId())) {
            subscription.setStripeSubscriptionId(event.stripeSubscriptionId());
        }
        if (event.planCode() != null) {
            subscription.setPlanCode(event.planCode());
        }
        // F-43 : l'engagement s'inscrit ici, au PAIEMENT CONFIRMÉ, jamais au clic — un checkout
        // annuel abandonné ne doit pas laisser un abonnement affiché comme annuel. Un événement qui
        // ne porte pas de périodicité n'en change aucune (voir BillingEvent : null = « ne dit rien »).
        if (event.billingPeriod() != null) {
            subscription.setBillingPeriod(event.billingPeriod());
        }
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setCurrentPeriodEnd(resolvePeriodEnd(event, subscription));
        subscriptionRepository.save(subscription);
    }

    /**
     * Applique un événement d'<b>option Atelier</b> (F-40) : statut de l'option, et rangement de
     * l'identifiant du <b>second</b> abonnement dans sa propre colonne.
     *
     * <p>Ce chemin ne touche ni {@code plan_code}, ni {@code status}, ni
     * {@code stripe_subscription_id} : l'option est un supplément, pas un changement d'offre. C'est
     * la propriété la plus importante de la feature côté webhook, et elle est figée par un test.</p>
     *
     * @param clearScheduledCancellation efface le terme programmé — vrai à la souscription (elle
     *                                   annule une résiliation antérieure) et à la fermeture
     *                                   effective (le terme est atteint, il n'a plus d'objet)
     */
    private void applyAtelierOptionState(BillingEvent event, SubscriptionStatus status,
            boolean clearScheduledCancellation) {
        Optional<Subscription> found = resolveForOption(event);
        if (found.isEmpty()) {
            log.warn("Événement d'option Atelier sans abonnement correspondant : ignoré");
            return;
        }
        Subscription subscription = found.get();
        if (StringUtils.hasText(event.stripeCustomerId())) {
            subscription.setStripeCustomerId(event.stripeCustomerId());
        }
        if (StringUtils.hasText(event.stripeSubscriptionId())) {
            subscription.setAtelierOptionStripeSubscriptionId(event.stripeSubscriptionId());
        }
        subscription.setAtelierOptionStatus(status);
        if (clearScheduledCancellation) {
            subscription.setAtelierOptionCancelAt(null);
        }
        subscriptionRepository.save(subscription);
    }

    /**
     * <b>Seconde ligne de défense</b> : applique un événement d'abonnement à l'option quand son
     * identifiant la désigne, et dit s'il a été consommé. Le fournisseur marque déjà les événements
     * d'option par métadonnée ({@code kind=atelier_option}) ; ce garde-fou couvre le cas où cette
     * métadonnée manquerait — sans lui, l'événement tomberait sur le repli « par client » et
     * écraserait le statut du <b>plan</b> : résilier l'option annulerait l'abonnement.
     *
     * <p>Le routage passe ici par l'identifiant d'abonnement fournisseur (index unique) : un index
     * est une preuve, une convention n'en est pas une.</p>
     *
     * @return {@code true} si l'événement portait sur l'option et a été appliqué à elle seule
     */
    private boolean applyToAtelierOptionIfConcerned(BillingEvent event, SubscriptionStatus status,
            boolean clearScheduledCancellation) {
        if (!StringUtils.hasText(event.stripeSubscriptionId())) {
            return false;
        }
        Optional<Subscription> found = subscriptionRepository
                .findByAtelierOptionStripeSubscriptionId(event.stripeSubscriptionId());
        if (found.isEmpty()) {
            return false;
        }
        Subscription subscription = found.get();
        subscription.setAtelierOptionStatus(status);
        if (clearScheduledCancellation) {
            subscription.setAtelierOptionCancelAt(null);
        }
        subscriptionRepository.save(subscription);
        return true;
    }

    private void applySubscriptionUpdate(BillingEvent event) {
        if (applyToAtelierOptionIfConcerned(event, SubscriptionStatus.fromStripe(event.status()), false)) {
            return;
        }
        Optional<Subscription> found = resolve(event);
        if (found.isEmpty()) {
            log.warn("customer.subscription.updated sans abonnement correspondant : ignoré");
            return;
        }
        Subscription subscription = found.get();
        if (StringUtils.hasText(event.stripeSubscriptionId())) {
            subscription.setStripeSubscriptionId(event.stripeSubscriptionId());
        }
        if (StringUtils.hasText(event.stripeCustomerId())) {
            subscription.setStripeCustomerId(event.stripeCustomerId());
        }
        if (event.planCode() != null) {
            subscription.setPlanCode(event.planCode());
        }
        if (event.billingPeriod() != null) {
            subscription.setBillingPeriod(event.billingPeriod());
        }
        subscription.setStatus(SubscriptionStatus.fromStripe(event.status()));
        if (event.currentPeriodEnd() != null) {
            subscription.setCurrentPeriodEnd(event.currentPeriodEnd());
        }
        subscriptionRepository.save(subscription);
    }

    private void applySubscriptionDeleted(BillingEvent event) {
        // Fin effective d'une option résiliée : le droit se referme, la date programmée s'efface,
        // et le plan n'est pas touché.
        if (applyToAtelierOptionIfConcerned(event, SubscriptionStatus.CANCELED, true)) {
            return;
        }
        Optional<Subscription> found = resolve(event);
        if (found.isEmpty()) {
            log.warn("customer.subscription.deleted sans abonnement correspondant : ignoré");
            return;
        }
        Subscription subscription = found.get();
        subscription.setStatus(SubscriptionStatus.CANCELED);
        subscriptionRepository.save(subscription);
    }

    /**
     * Résout l'abonnement ciblé par l'événement, sans jamais élargir au-delà d'un seul utilisateur :
     * on tente d'abord l'identifiant abonnement Stripe, puis le client Stripe, puis le {@code userId}.
     */
    /**
     * Résout l'abonnement visé par un événement d'<b>option</b>. Volontairement plus étroit que
     * {@link #resolve(BillingEvent)} : l'identifiant d'abonnement porté par l'événement est celui de
     * l'option, et le chercher dans {@code stripe_subscription_id} désignerait le plan d'un autre.
     * Seuls le client fournisseur et le {@code userId} sont des clés valides ici.
     */
    private Optional<Subscription> resolveForOption(BillingEvent event) {
        if (StringUtils.hasText(event.stripeSubscriptionId())) {
            Optional<Subscription> byOption = subscriptionRepository
                    .findByAtelierOptionStripeSubscriptionId(event.stripeSubscriptionId());
            if (byOption.isPresent()) {
                return byOption;
            }
        }
        if (event.userId() != null) {
            Optional<Subscription> byUser = subscriptionRepository.findByUserId(event.userId());
            if (byUser.isPresent()) {
                return byUser;
            }
        }
        if (StringUtils.hasText(event.stripeCustomerId())) {
            return subscriptionRepository.findByStripeCustomerId(event.stripeCustomerId());
        }
        return Optional.empty();
    }

    private Optional<Subscription> resolve(BillingEvent event) {
        if (StringUtils.hasText(event.stripeSubscriptionId())) {
            Optional<Subscription> bySub =
                    subscriptionRepository.findByStripeSubscriptionId(event.stripeSubscriptionId());
            if (bySub.isPresent()) {
                return bySub;
            }
        }
        if (StringUtils.hasText(event.stripeCustomerId())) {
            Optional<Subscription> byCustomer =
                    subscriptionRepository.findByStripeCustomerId(event.stripeCustomerId());
            if (byCustomer.isPresent()) {
                return byCustomer;
            }
        }
        if (event.userId() != null) {
            return subscriptionRepository.findByUserId(event.userId());
        }
        return Optional.empty();
    }

    /**
     * Détermine la fin de période. Pour un pass journée (DAILY, paiement unique sans abonnement
     * Stripe), l'événement ne porte pas de période : on applique 24 h. Sinon on garde la période
     * fournie (peuplée par {@code customer.subscription.updated}) ou l'existante.
     */
    private OffsetDateTime resolvePeriodEnd(BillingEvent event, Subscription subscription) {
        if (event.currentPeriodEnd() != null) {
            return event.currentPeriodEnd();
        }
        if (event.planCode() == PlanCode.DAILY) {
            return OffsetDateTime.now().plusDays(1);
        }
        return subscription.getCurrentPeriodEnd();
    }
}
