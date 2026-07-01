package fr.claudegateway.billing;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import fr.claudegateway.billing.provider.BillingEvent;
import fr.claudegateway.billing.provider.BillingProvider;

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

    public WebhookService(
            BillingProvider billingProvider,
            SubscriptionRepository subscriptionRepository) {
        this.billingProvider = billingProvider;
        this.subscriptionRepository = subscriptionRepository;
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
            case SUBSCRIPTION_UPDATED -> applySubscriptionUpdate(event);
            case SUBSCRIPTION_DELETED -> applySubscriptionDeleted(event);
            case UNHANDLED -> log.debug("Événement de facturation non géré, ignoré");
        }
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
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setCurrentPeriodEnd(resolvePeriodEnd(event, subscription));
        subscriptionRepository.save(subscription);
    }

    private void applySubscriptionUpdate(BillingEvent event) {
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
        subscription.setStatus(SubscriptionStatus.fromStripe(event.status()));
        if (event.currentPeriodEnd() != null) {
            subscription.setCurrentPeriodEnd(event.currentPeriodEnd());
        }
        subscriptionRepository.save(subscription);
    }

    private void applySubscriptionDeleted(BillingEvent event) {
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
