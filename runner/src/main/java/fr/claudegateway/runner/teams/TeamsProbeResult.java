package fr.claudegateway.runner.teams;

/**
 * Ce que la sonde a constaté (F-87 / SF-87-03).
 *
 * @param state     l'état de liaison, tel qu'il s'écrit dans la barre
 * @param health    ce que vaut la lecture : verdict, champs reconnus, version observée
 * @param observed  nombre de réponses de Teams réellement vues pendant la sonde
 * @param browser   le navigateur tel qu'il se déclare, ou {@code ""}
 * @param remedy    ce qu'il faut faire, ou {@code ""} quand il n'y a rien à faire
 */
public record TeamsProbeResult(TeamsLinkState state, TeamsHealth health, int observed,
        String browser, String remedy) {

    public TeamsProbeResult {
        state = state == null ? TeamsLinkState.BROWSER_NOT_DETECTED : state;
        health = health == null ? TeamsHealth.full(0) : health;
        observed = Math.max(0, observed);
        browser = browser == null ? "" : browser.strip();
        remedy = remedy == null ? "" : remedy.strip();
    }

    /**
     * Vrai si la sonde a pu <b>conclure</b> : elle a vu passer au moins une réponse de Teams.
     *
     * <p>Distinguer « rien observé » de « rien reconnu » n'est pas une subtilité : une alerte qui se
     * déclenche parce que personne n'écrivait devient une alerte qu'on ignore, et le jour où Teams
     * changera vraiment, plus personne ne la lira.</p>
     */
    public boolean conclusive() {
        return observed > 0;
    }

    /** La phrase lue par l'utilisateur. Elle dit l'état, ce qu'on sait, et ce qui reste à vérifier. */
    public String sentence() {
        switch (state) {
            case LINKED:
                if (!conclusive()) {
                    return "Teams relié" + browserSuffix()
                            + ". Aucune réponse observée pendant la vérification : la forme des "
                            + "réponses sera confirmée à la première lecture.";
                }
                return "Teams relié" + browserSuffix() + ". " + health.describe();
            case TEAMS_CHANGED:
                return health.describe();
            case BROWSER_NOT_DETECTED:
            default:
                return "Le navigateur du poste n'est pas relié.";
        }
    }

    private String browserSuffix() {
        return browser.isEmpty() ? "" : " (" + browser + ")";
    }
}
