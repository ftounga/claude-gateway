package fr.claudegateway.runner.teams;

/**
 * <b>La Vigie tourne seule</b> (F-122 / SF-122-03) : assemble le rapport de readiness que la boucle
 * d'arrière-plan fait remonter à la gateway.
 *
 * <p>Elle ne décide de rien de neuf : elle <b>combine</b> ce que SF-122-01 sait du Chrome managé et ce
 * que {@link TeamsSessionWatch} sait de la session Teams, plus le résultat d'un test de lecture de bout
 * en bout, en un {@link VigieReadinessReport} — la forme même qu'attend la check-list de SF-122-02.</p>
 */
public final class VigieBackground {

    private VigieBackground() {
    }

    /**
     * Assemble le rapport.
     *
     * @param chromeReachable le Chrome managé répond ({@link ManagedChrome#isReachable()})
     * @param sessionState    l'état de la session Teams ({@link TeamsSessionWatch#state()})
     * @param teamsReadTest   un test de lecture Teams de bout en bout a réussi
     */
    public static VigieReadinessReport assemble(boolean chromeReachable,
            TeamsSessionState sessionState, boolean teamsReadTest) {
        boolean connected = sessionState == TeamsSessionState.CONNECTED;
        boolean signInRequired = sessionState == TeamsSessionState.RELOGIN_REQUIRED;
        String detail = detailFor(chromeReachable, sessionState, teamsReadTest);
        return new VigieReadinessReport(chromeReachable, connected, signInRequired, teamsReadTest,
                detail);
    }

    private static String detailFor(boolean chromeReachable, TeamsSessionState sessionState,
            boolean teamsReadTest) {
        if (!chromeReachable) {
            return "Chrome managé non joignable.";
        }
        return switch (sessionState) {
            case RELOGIN_REQUIRED -> "Session Teams expirée : reconnexion requise.";
            case UNKNOWN -> "Session Teams pas encore observée.";
            case CONNECTED -> teamsReadTest
                    ? "Tout est prêt : Chrome managé joignable, Teams connecté, lecture vérifiée."
                    : "Teams connecté ; test de lecture en attente.";
        };
    }
}
