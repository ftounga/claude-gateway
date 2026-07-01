package fr.claudegateway.billing;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public SubscriptionService(
            SubscriptionRepository subscriptionRepository,
            BillingProperties properties) {
        this.subscriptionRepository = subscriptionRepository;
        this.properties = properties;
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
