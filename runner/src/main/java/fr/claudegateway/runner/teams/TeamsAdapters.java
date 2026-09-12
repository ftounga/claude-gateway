package fr.claudegateway.runner.teams;

/**
 * Le seul moyen d'obtenir un {@link TeamsAdapter} (F-87 / SF-87-01).
 *
 * <p>Les implémentations ne sont pas publiques : le reste du runner obtient une interface, jamais
 * une classe qui saurait ce qu'est une URL Teams. C'est le compilateur, et non une consigne
 * d'équipe, qui tient la règle de l'adaptateur unique.</p>
 */
public final class TeamsAdapters {

    private TeamsAdapters() {
    }

    /** L'adaptateur courant. Le jour où Microsoft changera, c'est ici qu'on choisira. */
    public static TeamsAdapter current() {
        return new TeamsAdapterV1();
    }
}
