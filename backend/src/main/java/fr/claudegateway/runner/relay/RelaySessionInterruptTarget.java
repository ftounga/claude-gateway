package fr.claudegateway.runner.relay;

/**
 * Marque d'interruption d'une session Managed Agent (F-32), posée ou retirée <b>localement</b> quand
 * elle arrive par le relais (F-38 / SF-38-13, contrat du relais §6).
 *
 * <p>Clef distincte de celle des tours d'atelier — un identifiant de session fournisseur, pas un
 * couple {@code userId:workspaceId}. Deux mécanismes, deux routes, deux enveloppes : les fusionner
 * mélangerait deux notions qui n'ont ni la même durée de vie ni le même émetteur.</p>
 */
public interface RelaySessionInterruptTarget {

    /**
     * Pose ({@code mark = true}) ou retire ({@code mark = false}) la marque d'interruption d'une
     * session sur ce pod. Le retrait sert au rattrapage : si le fournisseur refuse l'interruption,
     * aucun pod ne doit continuer d'afficher comme interrompu un tour qui ne l'a pas été.
     */
    void markSessionInterruptedLocally(String sessionId, boolean mark);
}
