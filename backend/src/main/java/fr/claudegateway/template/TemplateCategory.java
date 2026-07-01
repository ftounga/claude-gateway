package fr.claudegateway.template;

/**
 * Catégorie métier d'un modèle de prompt réutilisable. Valeurs fermées : sert uniquement au
 * classement/affichage. {@link #OTHER} est la valeur par défaut lorsqu'aucune catégorie n'est
 * fournie.
 */
public enum TemplateCategory {

    /** Modèle destiné à un audit (sécurité, conformité…). */
    AUDIT,

    /** Modèle destiné à la rédaction d'un rapport. */
    REPORT,

    /** Catégorie par défaut / usage général. */
    OTHER
}
