package fr.claudegateway.governance;

/**
 * Genre d'un fichier déposé par un paquet de gouvernance (F-51 / SF-51-01, étendu par F-92 /
 * SF-92-01).
 *
 * <p>Le genre décide <b>où</b> le fichier se pose, et c'est désormais sa raison d'être principale :
 * {@link #SKILL} et {@link #TEMPLATE} atterrissent dans <b>chaque projet</b> du poste, {@link #MAP}
 * <b>une seule fois, à la racine du poste</b>. Il sert aussi à ce que l'écran en dit avant
 * l'activation : « ce paquet dépose 2 skills, 3 gabarits et 6 fichiers de carte » se comprend,
 * « ce paquet dépose 11 fichiers » ne se comprend pas.</p>
 */
public enum GovernanceFileKind {

    /**
     * Un skill, déposé sous {@code .claude/skills/} — le produit lit déjà ce dossier et l'annonce au
     * modèle (AtelierChatService, {@code SKILL_PREFIXES}). Rien de neuf n'est donc requis pour
     * qu'un skill déposé serve : il sert au tour suivant.
     */
    SKILL,

    /** Un gabarit de travail ({@code STATE.md}, {@code PLAN-ACTION.md}…), déposé tel quel. */
    TEMPLATE,

    /**
     * Un fichier de <b>carte</b> (F-92), déposé à la <b>racine du poste</b> — jamais dans un projet.
     *
     * <p>C'est la pièce qui manquait : le produit savait porter le <b>travail</b>, pas le
     * <b>savoir</b>. La carte est l'endroit où la connaissance s'accumule, à côté des dossiers de
     * projets ; comme les projets sont des dossiers et la carte des fichiers, les deux ne se
     * confondent jamais, et <b>aucune convention de chemin n'est imposée</b> — la racine est celle
     * que le runner a déclarée, quelle qu'elle soit.</p>
     *
     * <p><b>Un poste sans machine n'a pas de racine</b> : le poste virtuel « Hébergé » (F-71) ne
     * reçoit donc aucun fichier de ce genre, et son absence n'est pas un échec de dépôt.</p>
     */
    MAP
}
