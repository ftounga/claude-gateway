package fr.claudegateway.quota;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Service;

import fr.claudegateway.billing.PlanCatalog;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.ProviderMode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionStatus;

/**
 * Résout l'entitlement d'un utilisateur (F-10) : combien de tokens son abonnement lui alloue pour
 * la période courante. Traduit l'état d'abonnement (F-09) en une allocation de tokens à partir de
 * la configuration {@link QuotaProperties}. Aucune donnée n'est persistée ici.
 *
 * <p>Règle (fail-closed) : seuls un abonnement payant (ACTIVE ou PAST_DUE en sursis) ou un essai
 * non expiré ouvrent un quota ; tout autre état résout à {@code 0} (accès bloqué).</p>
 *
 * <p><b>F-41 — le zéro ambigu.</b> Une offre {@link ProviderMode#BYOK} alloue légitimement
 * <b>0 jeton</b> : le client apporte sa clé, la gateway ne paie aucun jeton et ne facture que la
 * plateforme (PROJECT.md §11.8). Ce zéro-là ne doit <b>jamais</b> être lu comme le zéro d'un
 * abonnement expiré, qui lui doit bloquer. Le quota seul ne porte pas cette différence — un nombre
 * ne dit pas pourquoi il vaut zéro : c'est {@link #isCustomerKeyBilled(Subscription)} qui la porte,
 * explicitement, et sur laquelle le pré-vol de quota s'appuie.</p>
 */
@Service
public class EntitlementService {

    /** Statuts qui valent « abonnement en cours » : l'actif, et le sursis de paiement. */
    private static final Set<SubscriptionStatus> LIVE_STATUSES =
            EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE);

    private final QuotaProperties properties;
    private final PlanCatalog planCatalog;

    public EntitlementService(QuotaProperties properties, PlanCatalog planCatalog) {
        this.properties = properties;
        this.planCatalog = planCatalog;
    }

    /**
     * Allocation mensuelle de tokens pour l'abonnement fourni.
     *
     * @param subscription abonnement de l'utilisateur (jamais {@code null} — provisionné par F-09)
     * @return quota de tokens de la période (0 si l'abonnement n'ouvre aucun accès, 0 aussi pour une
     *         offre BYOK — voir {@link #isCustomerKeyBilled(Subscription)} pour les distinguer)
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

    /**
     * Vrai si les appels de cet utilisateur sont servis — et facturés — par <b>sa propre clé</b>
     * fournisseur, parce qu'il est sur une offre {@link ProviderMode#BYOK} en cours (F-41).
     *
     * <p>C'est la seule chose qui sépare un client BYOK payant d'un impayé : les deux résolvent
     * 0 jeton de quota plateforme, mais le premier a payé son abonnement et doit passer, le second
     * doit être bloqué. Un abonnement BYOK résilié, incomplet ou en essai rend {@code false} : le
     * fail-closed reste la règle par défaut.</p>
     *
     * @param subscription abonnement de l'utilisateur (jamais {@code null})
     * @return {@code true} si l'offre en cours est une offre BYOK
     */
    public boolean isCustomerKeyBilled(Subscription subscription) {
        return isLive(subscription.getStatus()) && isByokPlan(subscription.getPlanCode());
    }

    /** Vrai si le plan est une offre servie par la clé du client (mode fournisseur BYOK). */
    private boolean isByokPlan(PlanCode planCode) {
        if (planCode == null) {
            return false;
        }
        return planCatalog.plans().stream()
                .anyMatch(plan -> plan.code() == planCode && plan.providerMode() == ProviderMode.BYOK);
    }

    private static boolean isLive(SubscriptionStatus status) {
        return status != null && LIVE_STATUSES.contains(status);
    }

    private boolean isTrialActive(Subscription subscription) {
        OffsetDateTime trialEndsAt = subscription.getTrialEndsAt();
        return trialEndsAt == null || trialEndsAt.isAfter(OffsetDateTime.now());
    }
}
