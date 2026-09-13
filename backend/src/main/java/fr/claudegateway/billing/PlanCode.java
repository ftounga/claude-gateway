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
    /**
     * Offre Gold (ADR-012) : plan dédié qui débloque l'accès à l'Atelier (F-28). Depuis F-107 /
     * SF-107-03, affichée <b>« Gold Forge »</b> : le code ne change pas, aucun abonnement ne change.
     */
    GOLD,
    /**
     * Offre <b>BYOK</b> (F-41) : la plateforme seule, <b>aucune allocation de jetons</b>. Depuis
     * F-107 / SF-107-01, la Forge (l'Atelier) n'y est plus comprise : elle s'y achète par l'option,
     * à un prix propre à BYOK. Les appels sont servis par la clé Anthropic du client
     * (F-03) et facturés sur son propre compte fournisseur (PROJECT.md §11.8).
     */
    BYOK,
    /**
     * Offre <b>Gold Vigie</b> (F-107 / SF-107-03) : le quota de Gold et l'espace Vigie (Teams, Radar,
     * réunions) inclus ; la Forge s'y ajoute par l'option.
     */
    GOLD_VIGIE,
    /**
     * Offre <b>Gold complet</b> (F-107 / SF-107-03) : un seul quota, celui de Gold, et les deux espaces
     * inclus — la Vigie remisée de 25 %, la remise ne portant jamais sur les jetons.
     */
    GOLD_COMPLETE
}
