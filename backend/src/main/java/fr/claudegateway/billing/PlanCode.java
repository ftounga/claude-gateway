package fr.claudegateway.billing;

/**
 * Codes des plans du catalogue (F-09). Le code est stable et sert de clé de mapping vers le
 * price ID Stripe (résolu par configuration en SF-09-02), jamais l'inverse. Ajouter un plan =
 * ajouter une valeur ici + son entrée catalogue ({@link PlanCatalog}) — décision réversible.
 */
public enum PlanCode {
    SOLO,
    PRO,
    DAILY,
    /** Offre Gold (ADR-012) : plan dédié qui débloque l'accès à l'Atelier (F-28). */
    GOLD,
    /**
     * Offre <b>BYOK</b> (F-41) : la plateforme seule. Accès complet, Atelier compris, mais
     * <b>aucune allocation de jetons</b> — les appels sont servis par la clé Anthropic du client
     * (F-03) et facturés sur son propre compte fournisseur (PROJECT.md §11.8).
     */
    BYOK
}
