package fr.claudegateway.runner.teams;

/**
 * <b>Les sources d'une synchro, dans l'ordre</b> (F-100 / SF-100-05) : la collecte Teams d'abord — les
 * échanges sont ce que le résumé du matin attend le plus —, puis le dossier de dépôt, dont la transcription
 * peut durer. Les couvertures sont fusionnées, l'issue la plus sévère l'emporte.
 */
final class RadarCollectors {

    private RadarCollectors() {
    }

    /** Teams, puis le dossier de dépôt ({@code null} : pas de dossier sur ce runner). */
    static RadarCollector chain(RadarCollector teams, RadarDepositCollector deposit) {
        return (assignment, context) -> {
            RadarCollector.Outcome outcome = teams.collect(assignment, context);
            if (deposit == null || context.stopped()) {
                return outcome;
            }
            return RadarDepositCollector.merge(outcome, deposit.collect(assignment, context));
        };
    }
}
