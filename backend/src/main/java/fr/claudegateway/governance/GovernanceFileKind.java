package fr.claudegateway.governance;

/**
 * Genre d'un fichier déposé par un paquet de gouvernance (F-51 / SF-51-01).
 *
 * <p>Les deux genres passent par le <b>même mécanisme</b> — un fichier créé dans le projet s'il n'y
 * est pas. Ils ne diffèrent que par ce que l'écran en dit avant l'activation : « ce paquet dépose
 * 2 skills et 1 gabarit » se comprend, « ce paquet dépose 3 fichiers » ne se comprend pas.</p>
 */
public enum GovernanceFileKind {

    /**
     * Un skill, déposé sous {@code .claude/skills/} — le produit lit déjà ce dossier et l'annonce au
     * modèle (AtelierChatService, {@code SKILL_PREFIXES}). Rien de neuf n'est donc requis pour
     * qu'un skill déposé serve : il sert au tour suivant.
     */
    SKILL,

    /** Un gabarit de travail ({@code STATE.md}, {@code PLAN-ACTION.md}…), déposé tel quel. */
    TEMPLATE
}
