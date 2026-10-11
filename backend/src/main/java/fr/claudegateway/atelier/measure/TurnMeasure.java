package fr.claudegateway.atelier.measure;

import java.time.Instant;
import java.util.UUID;

/**
 * <b>La mesure d'un tour</b> (F-188 / SF-188-03) : des nombres et un motif d'arrêt, jamais de
 * contenu. Voir {@code docs/ops/mesure-par-tour.md} pour les lire.
 *
 * @param iterations           passes de la boucle principale
 * @param stopCause            motif d'arrêt, le même libellé que le journal « Tour d'atelier terminé »
 * @param maxContextTokens     plus grand prompt d'une passe principale (cache compris)
 * @param totalContextTokens   somme des prompts des passes principales (la moyenne s'en déduit)
 * @param firstPassCacheWrite  réécriture du cache à la première passe
 * @param laterCacheWrite      réécriture du cache des passes suivantes
 * @param runnerRefusals       résultats {@code denied} rendus au modèle
 * @param runnerBusyWaits      attentes « poste occupé » (F-188 / SF-188-02)
 */
public record TurnMeasure(UUID userId, UUID workspaceId, UUID hostId, String model,
        Instant occurredAt, long durationMs, int iterations, String stopCause,
        long maxContextTokens, long totalContextTokens, long firstPassCacheWrite,
        long laterCacheWrite, long inputTokens, long outputTokens, long cacheReadTokens,
        long cacheWriteTokens, int webSearchRequests, int runnerRefusals, int runnerBusyWaits) {
}
