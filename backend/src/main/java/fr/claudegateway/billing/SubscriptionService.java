package fr.claudegateway.billing;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import fr.claudegateway.billing.provider.BillingProvider;
import fr.claudegateway.billing.provider.ChangePlanCommand;

/**
 * Logique métier des abonnements (F-09). Point d'entrée d'isolation : toutes les opérations prennent
 * le {@code userId} du contexte de sécurité (jamais un paramètre client) et filtrent dessus.
 *
 * <p>SF-09-01 fournit le provisionnement <b>paresseux</b> et idempotent de l'essai gratuit : au
 * premier accès d'un utilisateur sans abonnement, un essai {@link SubscriptionStatus#TRIALING} est
 * créé pour {@code trialDays} jours. Le peuplement des identifiants Stripe et les transitions de
 * statut arrivent en SF-09-02 (webhook).</p>
 */
@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final BillingProperties properties;
    private final PlanCatalog planCatalog;
    private final BillingProvider billingProvider;

    public SubscriptionService(
            SubscriptionRepository subscriptionRepository,
            BillingProperties properties,
            PlanCatalog planCatalog,
            BillingProvider billingProvider) {
        this.subscriptionRepository = subscriptionRepository;
        this.properties = properties;
        this.planCatalog = planCatalog;
        this.billingProvider = billingProvider;
    }

    /**
     * Renvoie l'abonnement de l'utilisateur, en provisionnant un essai gratuit s'il n'en a pas encore.
     * Idempotent : un seul abonnement par utilisateur (contrainte {@code unique(user_id)}). En cas de
     * course entre deux appels concurrents, la violation d'unicité est rattrapée par une relecture.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @return l'abonnement existant ou l'essai fraîchement provisionné
     */
    @Transactional
    public Subscription getOrCreateForUser(UUID userId) {
        return subscriptionRepository.findByUserId(userId)
                .orElseGet(() -> provisionTrial(userId));
    }

    /**
     * Change le plan de l'abonnement <b>existant</b> de l'utilisateur (upgrade/downgrade, SF-21-05).
     * Met à jour l'abonnement Stripe avec proratisation via le {@link BillingProvider}, puis reflète
     * le nouveau plan localement (le webhook {@code customer.subscription.updated} confirmera).
     *
     * @param userId      utilisateur authentifié (contexte de sécurité — isolation)
     * @param planCodeRaw code du plan cible fourni par le client
     * @return l'abonnement mis à jour
     * @throws UnknownPlanException            plan absent/inconnu ou sans price configuré
     * @throws NoActiveSubscriptionException   aucun abonnement payant actif (encore en essai)
     */
    @Transactional
    public Subscription changePlan(UUID userId, String planCodeRaw) {
        PlanCode target = parsePlan(planCodeRaw);
        if (!planCatalog.contains(target)) {
            throw new UnknownPlanException("Plan inconnu : " + planCodeRaw);
        }
        String newPriceId = properties.stripe().priceId(target);
        if (!StringUtils.hasText(newPriceId)) {
            throw new UnknownPlanException("Aucun price configuré pour le plan " + target + ".");
        }
        Subscription subscription = getOrCreateForUser(userId);
        if (!StringUtils.hasText(subscription.getStripeSubscriptionId())) {
            throw new NoActiveSubscriptionException(
                    "Aucun abonnement actif : souscrivez un plan avant de le changer.");
        }
        billingProvider.changeSubscriptionPlan(
                new ChangePlanCommand(subscription.getStripeSubscriptionId(), newPriceId));
        // Reflet local optimiste ; le webhook customer.subscription.updated confirmera l'état Stripe.
        subscription.setPlanCode(target);
        return subscriptionRepository.save(subscription);
    }

    private static PlanCode parsePlan(String planCodeRaw) {
        if (planCodeRaw == null || planCodeRaw.isBlank()) {
            throw new UnknownPlanException("Plan non fourni.");
        }
        try {
            return PlanCode.valueOf(planCodeRaw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new UnknownPlanException("Plan inconnu : " + planCodeRaw);
        }
    }

    private Subscription provisionTrial(UUID userId) {
        Subscription trial = Subscription.builder()
                .userId(userId)
                .status(SubscriptionStatus.TRIALING)
                .planCode(null)
                .trialEndsAt(OffsetDateTime.now().plusDays(properties.trialDays()))
                .build();
        try {
            return subscriptionRepository.save(trial);
        } catch (DataIntegrityViolationException concurrentCreation) {
            // Un autre appel concurrent a créé l'essai en premier : on relit l'unique ligne.
            return subscriptionRepository.findByUserId(userId)
                    .orElseThrow(() -> concurrentCreation);
        }
    }
}
