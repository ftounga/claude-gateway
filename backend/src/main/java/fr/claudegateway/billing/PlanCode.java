package fr.claudegateway.billing;

/**
 * Codes des plans du catalogue (F-09). Le code est stable et sert de clé de mapping vers le
 * price ID Stripe (résolu par configuration en SF-09-02), jamais l'inverse. Ajouter un plan =
 * ajouter une valeur ici + son entrée catalogue ({@link PlanCatalog}) — décision réversible.
 */
public enum PlanCode {
    SOLO,
    PRO,
    /**
     * Pass journée — <b>retiré du catalogue</b> le 2026-09-07 (SF-09-04) : il n'a jamais eu de price
     * ID Stripe, donc n'a jamais été vendable. La constante est <b>conservée</b> parce que
     * {@code subscriptions.plan_code} est un {@code varchar(32)} sans contrainte d'énumération : la
     * retirer ferait échouer la lecture d'un abonnement qui la porterait, transformant un retrait
     * commercial en incident. Ne pas confondre avec le <b>pack de recharge</b> {@code DAY} de
     * {@code TopUpCatalog}, qui porte le même nom commercial et qui, lui, se vend.
     */
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
