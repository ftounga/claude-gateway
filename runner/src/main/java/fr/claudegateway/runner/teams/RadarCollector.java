package fr.claudegateway.runner.teams;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>La collecte</b> d'une synchro (F-100) : ce qui lit, sur la machine, les sources du Radar et fait
 * remonter les lots. SF-100-02 pose l'interface ; la collecte Teams est SF-100-03, le dossier de dépôt
 * SF-100-05.
 */
interface RadarCollector {

    /**
     * Collecte. Ne lève pas : un échec est une issue, avec sa couverture.
     *
     * @param assignment ce que la gateway a demandé
     * @param context    battement, lots, arrêt
     */
    Outcome collect(RadarAssignment assignment, RadarSyncContext context);

    /**
     * L'issue d'une collecte.
     *
     * @param status   {@code SUCCEEDED}, {@code PARTIAL} ou {@code FAILED}
     * @param coverage la couverture (forme fixée en SF-100-04)
     */
    record Outcome(String status, ObjectNode coverage) {
    }

    /** Pas de collecteur sur ce runner : l'échec est <b>nommé</b>, jamais une synchro vide réussie. */
    static RadarCollector unavailable() {
        return (assignment, context) -> {
            ObjectNode coverage = new ObjectMapper().createObjectNode();
            ObjectNode failure = coverage.putObject("failure");
            failure.put("code", "COLLECTOR_UNAVAILABLE");
            failure.put("sentence", "Collecte Teams non disponible sur ce runner : mettez-le à jour.");
            return new Outcome("FAILED", coverage);
        };
    }
}
