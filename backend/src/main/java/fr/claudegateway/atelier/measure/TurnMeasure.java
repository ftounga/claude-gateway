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
 * @param chantierNumber       le fil du tour (F-221 / SF-221-01) ; {@code null} = fil commun
 * @param ribbon               la divergence du ruban (F-229 / SF-229-01) ; {@code null} = non mesurée
 */
public record TurnMeasure(UUID userId, UUID workspaceId, UUID hostId, String model,
        Instant occurredAt, long durationMs, int iterations, String stopCause,
        long maxContextTokens, long totalContextTokens, long firstPassCacheWrite,
        long laterCacheWrite, long inputTokens, long outputTokens, long cacheReadTokens,
        long cacheWriteTokens, int webSearchRequests, int runnerRefusals, int runnerBusyWaits,
        Integer chantierNumber, Ribbon ribbon) {

    /**
     * <b>Le ruban du tour</b> (F-229 / SF-229-01) : des hachages et des nombres, jamais de contenu.
     * Chaque champ peut manquer ({@code null}) : la mesure est best-effort.
     *
     * @param fingerprint    empreinte JSON {@code [[hachage, taille], …]} de la dernière requête du tour
     * @param divergence     où et pourquoi le premier appel du tour a quitté le ruban du tour précédent
     * @param replayCutChars caractères retirés au stockage par rapport à ce qui a été envoyé (résultats
     *                       coupés, étapes abandonnées) : ce qu'ajouterait un rejeu intégral
     */
    public record Ribbon(String fingerprint, RibbonDivergence divergence, Long replayCutChars) {
    }
}
