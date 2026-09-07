package fr.claudegateway.billing;

/**
 * Périodicité de facturation (F-09, étendue par F-43). Trois régimes d'encaissement :
 *
 * <ul>
 *   <li>{@link #MONTHLY} — abonnement mensuel récurrent, le régime par défaut ;</li>
 *   <li>{@link #DAILY} — pass journée, paiement <b>unique</b> (PROJECT.md §11.10) ;</li>
 *   <li>{@link #YEARLY} — engagement annuel encaissé d'avance (F-43).</li>
 * </ul>
 *
 * <p><b>Cette énumération ne dit rien du quota.</b> L'allocation de jetons reste <b>mensuelle</b>
 * quelle que soit la valeur portée ici : elle est résolue par {@code QuotaProperties.tokensForPlan}
 * à partir du seul {@link PlanCode}, et la période de consommation est le mois calendaire UTC
 * ({@code QuotaService.currentPeriodStart}). L'engagement est annuel, l'allocation reste mensuelle —
 * sans quoi un abonné annuel pourrait épuiser douze mois de jetons dès le premier.</p>
 */
public enum BillingPeriod {
    MONTHLY,
    DAILY,
    /** Engagement annuel (F-43) : payé d'avance pour douze mois, alloué mois par mois. */
    YEARLY
}
