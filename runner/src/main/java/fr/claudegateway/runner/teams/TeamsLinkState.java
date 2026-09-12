package fr.claudegateway.runner.teams;

/**
 * <b>Les trois états de la liaison</b> (F-87 / SF-87-03), et rien de plus.
 *
 * <p>Ce sont exactement les trois mots de l'indicateur dans la barre du terminal. La liste est
 * close : un quatrième état demanderait une quatrième couleur, et la charte en compte déjà autant
 * qu'elle peut en porter.</p>
 *
 * <p>Les nuances — pas d'onglet Teams, session non ouverte, machine éteinte — ne sont pas des états
 * de plus : elles vivent dans la <b>phrase</b> et dans le <b>remède</b>, là où elles servent.</p>
 */
public enum TeamsLinkState {

    /** Le navigateur est relié et Teams se lit. */
    LINKED("Teams relié"),

    /** Aucun navigateur joignable — ou aucune machine, ou Teams pas ouvert. */
    BROWSER_NOT_DETECTED("Teams : navigateur non détecté"),

    /** La forme des réponses n'est plus celle que l'adaptateur connaît. */
    TEAMS_CHANGED("Teams a changé");

    private final String label;

    TeamsLinkState(String label) {
        this.label = label;
    }

    /** Le libellé écrit à l'écran. Toujours écrit — la couleur ne porte jamais seule l'information. */
    public String label() {
        return label;
    }
}
