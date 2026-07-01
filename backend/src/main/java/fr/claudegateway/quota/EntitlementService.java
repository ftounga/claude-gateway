package fr.claudegateway.quota;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;

import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionStatus;

/**
 * Résout l'entitlement d'un utilisateur (F-10) : combien de tokens son abonnement lui alloue pour
 * la période courante. Traduit l'état d'abonnement (F-09) en une allocation de tokens à partir de
 * la configuration {@link QuotaProperties}. Aucune donnée n'est persistée ici.
 *
 * <p>Règle (fail-closed) : seuls un abonnement payant (ACTIVE ou PAST_DUE en sursis) ou un essai
 * non expiré ouvrent un quota ; tout autre état résout à {@code 0} (accès bloqué).</p>
 */
@Service
public class EntitlementService {

    private final QuotaProperties properties;

    public EntitlementService(QuotaProperties properties) {
        this.properties = properties;
    }

    /**
     * Allocation mensuelle de tokens pour l'abonnement fourni.
     *
     * @param subscription abonnement de l'utilisateur (jamais {@code null} — provisionné par F-09)
     * @return quota de tokens de la période (0 si l'abonnement n'ouvre aucun accès)
     */
    public long resolveMonthlyTokenQuota(Subscription subscription) {
        SubscriptionStatus status = subscription.getStatus();
        if (status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.PAST_DUE) {
            // Abonnement payant (PAST_DUE = accès en sursis) : quota du plan souscrit.
            return properties.tokensForPlan(subscription.getPlanCode());
        }
        if (status == SubscriptionStatus.TRIALING && isTrialActive(subscription)) {
            return properties.trialTokens();
        }
        return 0L;
    }

    private boolean isTrialActive(Subscription subscription) {
        OffsetDateTime trialEndsAt = subscription.getTrialEndsAt();
        return trialEndsAt == null || trialEndsAt.isAfter(OffsetDateTime.now());
    }
}
