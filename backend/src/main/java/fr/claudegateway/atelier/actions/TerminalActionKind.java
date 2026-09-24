package fr.claudegateway.atelier.actions;

/**
 * La nature d'une action du terminal (F-154 / SF-154-01) : elle oriente ce que l'écran propose.
 *
 * <p>{@link #MESSAGE} ouvre l'envoi d'un courriel (F-110) pré-rempli ; {@link #ACTION} ne propose
 * rien d'autre que « c'est fait ». Rien de plus : la nature décrit le geste attendu, pas une
 * catégorie métier.</p>
 */
public enum TerminalActionKind {

    /** Faire quelque chose soi-même : demander un accès, lancer une machine, vérifier un droit. */
    ACTION,

    /** Écrire à quelqu'un. Le menu proposera « Envoyer le message ». */
    MESSAGE
}
