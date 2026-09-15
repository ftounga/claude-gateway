package fr.claudegateway.vigie;

/**
 * Ce que le runner rapporte de l'état de mise en service de la Vigie pour un poste (F-122 / SF-122-02).
 *
 * <p>C'est le corps de {@code POST /runner/vigie/readiness}. Le poste n'y figure <b>pas</b> : il vient
 * du jeton runner présenté, jamais d'un champ du corps (isolation). La <b>production</b> de cet
 * instantané — l'exécution réelle des sondes Chrome et Teams et du test de lecture — est du ressort de
 * SF-122-03 (la Vigie qui tourne seule) ; SF-122-02 en définit le contrat et l'agrégation.</p>
 *
 * @param chromeReachable     le Chrome managé répond sur son port de débogage local
 * @param teamsConnected      la session Teams est ouverte dans la fenêtre managée
 * @param teamsSignInRequired une identification Teams est requise (fenêtre à ouvrir pour le login)
 * @param teamsReadTest       un test de lecture Teams de bout en bout a réussi
 * @param detail              précision optionnelle pour l'affichage (jamais un secret)
 */
public record VigieReadinessSnapshot(
        boolean chromeReachable,
        boolean teamsConnected,
        boolean teamsSignInRequired,
        boolean teamsReadTest,
        String detail) {
}
