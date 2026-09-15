package fr.claudegateway.runner.teams;

/**
 * Ce que le runner fait remonter de l'état de mise en service de la Vigie (F-122 / SF-122-03).
 *
 * <p>Mêmes champs que l'instantané consommé par SF-122-02 côté gateway : la boucle d'arrière-plan
 * l'assemble ({@link VigieBackground}) et le fait remonter ({@link VigieReadinessUploader}) sur
 * {@code POST /runner/vigie/readiness}. Aucun secret n'y entre : uniquement des booléens et un libellé.</p>
 *
 * @param chromeReachable     le Chrome managé répond sur son port de débogage local
 * @param teamsConnected      la session Teams est ouverte
 * @param teamsSignInRequired une reconnexion Teams est demandée
 * @param teamsReadTest       un test de lecture Teams de bout en bout a réussi
 * @param detail              libellé court pour l'affichage (jamais un secret)
 */
public record VigieReadinessReport(
        boolean chromeReachable,
        boolean teamsConnected,
        boolean teamsSignInRequired,
        boolean teamsReadTest,
        String detail) {
}
