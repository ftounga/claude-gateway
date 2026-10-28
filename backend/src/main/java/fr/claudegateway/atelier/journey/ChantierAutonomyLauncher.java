package fr.claudegateway.atelier.journey;

import fr.claudegateway.atelier.Workspace;

/**
 * <b>Programmer le départ d'un chantier en autonome depuis la conversation</b> (F-219 / SF-219-04). Implémenté
 * par le service d'autonomie ; l'interface vit ici pour que le parcours ne dépende pas de son paquet.
 */
public interface ChantierAutonomyLauncher {

    /**
     * Vérifie le préflight et les gardes de départ du chantier {@code number}, puis programme son départ dès
     * que le sujet sera libre.
     *
     * @param workspace le terminal du tour, <b>déjà</b> possédé par {@code userId}
     * @return le résultat à rendre au modèle
     */
    default Result schedule(java.util.UUID userId, Workspace workspace, int number) {
        return schedule(userId, workspace, number, AutonomyMode.STRICT);
    }

    /**
     * Même chose, dans le mode donné (SF-219-05) : {@link AutonomyMode#UNTIL_BLOCKED} part dès qu'une étape
     * est à la portée de l'outil.
     */
    Result schedule(java.util.UUID userId, Workspace workspace, int number, AutonomyMode mode);

    /** Ce que l'outil rend au modèle. */
    record Result(boolean scheduled, String message) {
    }
}
