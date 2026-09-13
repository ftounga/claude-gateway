package fr.claudegateway.radar.analysis;

import java.util.UUID;

/** Les lots d'une synchro dans un statut, comptés (F-101 / SF-101-01). Aucun texte. */
public record RadarAnalysisSyncCount(UUID syncId, RadarAnalysisBatchStatus status, Long batches,
        Long exchanges, Long messages, Long retained, Long subjectsAttached, Long subjectsCreated,
        Long triageInputTokens, Long triageOutputTokens, Long extractionInputTokens,
        Long extractionOutputTokens, Long cacheReadTokens, Long cacheWriteTokens) {

    /** La consommation de ces lots. */
    public RadarAnalysisTokens tokens() {
        return new RadarAnalysisTokens(n(triageInputTokens), n(triageOutputTokens), n(extractionInputTokens),
                n(extractionOutputTokens), n(cacheReadTokens), n(cacheWriteTokens));
    }

    static long n(Long value) {
        return value == null ? 0L : value;
    }
}
