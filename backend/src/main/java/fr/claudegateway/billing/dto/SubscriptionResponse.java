package fr.claudegateway.billing.dto;

import java.time.OffsetDateTime;

import fr.claudegateway.billing.Subscription;

/**
 * Représentation de l'abonnement de l'utilisateur courant exposée au client (F-09).
 *
 * <p>Volontairement <b>expurgée</b> des identifiants Stripe ({@code stripe_customer_id},
 * {@code stripe_subscription_id}) : ceux-ci sont internes et ne doivent jamais transiter vers le
 * navigateur (PROJECT.md §11.14).</p>
 *
 * @param status            statut courant de l'abonnement
 * @param planCode          code du plan payant, ou {@code null} en essai
 * @param trialEndsAt       fin de l'essai gratuit, ou {@code null}
 * @param currentPeriodEnd  fin de la période de facturation courante, ou {@code null}
 * @param billingPeriod     périodicité d'<b>engagement</b> ({@code MONTHLY}, {@code YEARLY},
 *                          {@code DAILY}), ou {@code null} si aucun engagement n'est enregistré
 *                          (essai, ou abonnement antérieur à F-43). Ne dit <b>rien</b> du quota :
 *                          l'allocation de jetons reste mensuelle quelle que soit sa valeur.
 * @param customerKeyBilled vrai si les appels sont servis — et facturés — par la clé du client
 *                          (offre BYOK en cours, F-41). L'écran s'en sert pour ne pas présenter un
 *                          quota nul comme un blocage. Le champ porte le résultat du <b>prédicat
 *                          serveur</b> ({@code EntitlementService.isCustomerKeyBilled}), pas une
 *                          comparaison de code de plan : le client reflète la décision du serveur au
 *                          lieu de la re-dériver, et ne peut donc plus s'en écarter.
 */
public record SubscriptionResponse(
        String status,
        String planCode,
        OffsetDateTime trialEndsAt,
        OffsetDateTime currentPeriodEnd,
        boolean customerKeyBilled,
        String billingPeriod) {

    public static SubscriptionResponse from(Subscription subscription, boolean customerKeyBilled) {
        return new SubscriptionResponse(
                subscription.getStatus().name(),
                subscription.getPlanCode() != null ? subscription.getPlanCode().name() : null,
                subscription.getTrialEndsAt(),
                subscription.getCurrentPeriodEnd(),
                customerKeyBilled,
                subscription.getBillingPeriod() != null
                        ? subscription.getBillingPeriod().name()
                        : null);
    }
}
