package fr.claudegateway.radar.analysis;

import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.quota.TurnTokens;

/**
 * La consommation d'une analyse, <b>par passe et par nature</b> (F-101) : c'est ce qui permet de
 * relever le coût réel d'une synchro (SF-101-05) et de le rapporter à l'essai de F-107.
 *
 * @param triageInputTokens      entrée plein tarif du tri
 * @param triageOutputTokens     sortie du tri
 * @param extractionInputTokens  entrée plein tarif de l'extraction
 * @param extractionOutputTokens sortie de l'extraction
 * @param cacheReadTokens        lus du cache, toutes passes
 * @param cacheWriteTokens       écrits dans le cache, toutes passes
 */
public record RadarAnalysisTokens(long triageInputTokens, long triageOutputTokens,
        long extractionInputTokens, long extractionOutputTokens, long cacheReadTokens,
        long cacheWriteTokens) {

    public static final RadarAnalysisTokens NONE = new RadarAnalysisTokens(0, 0, 0, 0, 0, 0);

    public RadarAnalysisTokens {
        triageInputTokens = Math.max(0, triageInputTokens);
        triageOutputTokens = Math.max(0, triageOutputTokens);
        extractionInputTokens = Math.max(0, extractionInputTokens);
        extractionOutputTokens = Math.max(0, extractionOutputTokens);
        cacheReadTokens = Math.max(0, cacheReadTokens);
        cacheWriteTokens = Math.max(0, cacheWriteTokens);
    }

    /** Un appel de tri. */
    public static RadarAnalysisTokens ofTriage(ChatCompletionResult result) {
        return result == null ? NONE : new RadarAnalysisTokens(result.fullPriceInputTokens(),
                result.outputTokens(), 0, 0, result.cacheReadTokens(), result.cacheWriteTokens());
    }

    /** Un appel d'extraction. */
    public static RadarAnalysisTokens ofExtraction(ChatCompletionResult result) {
        return result == null ? NONE : new RadarAnalysisTokens(0, 0, result.fullPriceInputTokens(),
                result.outputTokens(), result.cacheReadTokens(), result.cacheWriteTokens());
    }

    /** La somme. */
    public RadarAnalysisTokens plus(RadarAnalysisTokens other) {
        if (other == null) {
            return this;
        }
        return new RadarAnalysisTokens(triageInputTokens + other.triageInputTokens,
                triageOutputTokens + other.triageOutputTokens,
                extractionInputTokens + other.extractionInputTokens,
                extractionOutputTokens + other.extractionOutputTokens,
                cacheReadTokens + other.cacheReadTokens, cacheWriteTokens + other.cacheWriteTokens);
    }

    /** Volume total, toutes natures confondues — l'unité de la réserve. */
    public long total() {
        return triageInputTokens + triageOutputTokens + extractionInputTokens + extractionOutputTokens
                + cacheReadTokens + cacheWriteTokens;
    }

    /** Les mêmes jetons, dans la forme que le calcul de coût connaît (F-63). */
    public TurnTokens turnTokens() {
        return new TurnTokens(triageInputTokens + extractionInputTokens,
                triageOutputTokens + extractionOutputTokens, cacheReadTokens, cacheWriteTokens);
    }
}
