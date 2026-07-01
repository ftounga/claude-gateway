package fr.claudegateway.billing;

/**
 * Codes des plans du catalogue (F-09). Le code est stable et sert de clé de mapping vers le
 * price ID Stripe (résolu par configuration en SF-09-02), jamais l'inverse. Ajouter un plan =
 * ajouter une valeur ici + son entrée catalogue ({@link PlanCatalog}) — décision réversible.
 */
public enum PlanCode {
    SOLO,
    PRO,
    DAILY
}
