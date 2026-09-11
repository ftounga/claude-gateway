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
import fr.claudegateway.billing.seat.SeatQuotaService;

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
 *
 * <p><b>F-65 — deux allocations, pas une.</b> {@link #resolveMonthlyTokenQuota(Subscription)} reste
 * l'allocation du <b>plan</b>, que le catalogue annonce ; {@link
 * #resolveEffectiveMonthlyTokenQuota(Subscription)} y ajoute la part apportée par les <b>postes
 * supplémentaires</b>, et c'est elle qu'opposent le pré-vol, la jauge et le seuil d'alerte. Les
 * confondre ferait dire au catalogue des choses différentes selon le lecteur.</p>
 */
@Service
public class EntitlementService {

    /** Statuts qui valent « abonnement en cours » : l'actif, et le sursis de paiement. */
    private static final Set<SubscriptionStatus> LIVE_STATUSES =
            EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE);

    private final QuotaProperties properties;
    private final PlanCatalog planCatalog;
    private final SeatQuotaService seatQuotaService;

    public EntitlementService(
            QuotaProperties properties,
            PlanCatalog planCatalog,
            SeatQuotaService seatQuotaService) {
        this.properties = properties;
        this.planCatalog = planCatalog;
        this.seatQuotaService = seatQuotaService;
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
     * Allocation mensuelle <b>effective</b> : celle du plan, <b>plus</b> la part de jetons apportée
     * par les postes supplémentaires (F-65).
     *
     * <p>C'est cette méthode — et non {@link #resolveMonthlyTokenQuota(Subscription)} — que doivent
     * appeler le pré-vol, la jauge et le seuil d'alerte : ce que le quota oppose et ce que l'écran
     * annonce doivent être le même nombre. {@code resolveMonthlyTokenQuota} reste l'allocation du
     * <b>plan seul</b>, parce que le catalogue ({@code GET /billing/plans}) doit continuer d'annoncer
     * ce que le plan donne, indépendamment du nombre de postes de celui qui le regarde.</p>
     *
     * <p><b>Le supplément est un abonnement payant, pas un cadeau</b> : l'apport n'existe que sous
     * un abonnement en cours (ACTIVE ou PAST_DUE). Un essai n'en reçoit pas — personne ne facture
     * un poste supplémentaire à un essai gratuit — et une offre BYOK non plus : la plateforme n'y
     * alloue aucun jeton par contrat (F-41), et ce zéro-là doit rester un zéro.</p>
     *
     * @param subscription abonnement de l'utilisateur (jamais {@code null})
     * @return quota de la période, postes supplémentaires compris
     */
    public long resolveEffectiveMonthlyTokenQuota(Subscription subscription) {
        long planQuota = resolveMonthlyTokenQuota(subscription);
        if (!isLive(subscription.getStatus()) || isCustomerKeyBilled(subscription)) {
            return planQuota;
        }
        return planQuota + seatQuotaService.grantedTokens(subscription.getUserId());
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
