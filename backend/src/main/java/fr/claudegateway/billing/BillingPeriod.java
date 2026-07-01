package fr.claudegateway.billing;

/**
 * Périodicité de facturation d'un plan (F-09) : abonnement mensuel récurrent ou pass journée
 * (daily pass, PROJECT.md §11.10).
 */
public enum BillingPeriod {
    MONTHLY,
    DAILY
}
