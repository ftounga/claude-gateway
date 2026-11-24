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
 * @param provider             ce que le fournisseur a dit des appels du tour (F-276 / SF-276-01) ;
 *                             {@code null} = rien de connu
 */
public record TurnMeasure(UUID userId, UUID workspaceId, UUID hostId, String model,
        Instant occurredAt, long durationMs, int iterations, String stopCause,
        long maxContextTokens, long totalContextTokens, long firstPassCacheWrite,
        long laterCacheWrite, long inputTokens, long outputTokens, long cacheReadTokens,
        long cacheWriteTokens, int webSearchRequests, int runnerRefusals, int runnerBusyWaits,
        Integer chantierNumber, Ribbon ribbon, Provider provider) {

    /** Forme d'avant F-276 / SF-276-01 : rien de connu du fournisseur. */
    public TurnMeasure(UUID userId, UUID workspaceId, UUID hostId, String model,
            Instant occurredAt, long durationMs, int iterations, String stopCause,
            long maxContextTokens, long totalContextTokens, long firstPassCacheWrite,
            long laterCacheWrite, long inputTokens, long outputTokens, long cacheReadTokens,
            long cacheWriteTokens, int webSearchRequests, int runnerRefusals, int runnerBusyWaits,
            Integer chantierNumber, Ribbon ribbon) {
        this(userId, workspaceId, hostId, model, occurredAt, durationMs, iterations, stopCause, maxContextTokens,
                totalContextTokens, firstPassCacheWrite, laterCacheWrite, inputTokens, outputTokens, cacheReadTokens,
                cacheWriteTokens, webSearchRequests, runnerRefusals, runnerBusyWaits, chantierNumber, ribbon, null);
    }

    /**
     * <b>Ce que le fournisseur a dit du tour</b> (F-276 / SF-276-01) : son diagnostic de cache au premier
     * appel, et le raisonnement envoyé puis écarté. Des types, des nombres et un identifiant de réponse —
     * jamais de contenu. Chaque champ peut manquer.
     *
     * @param firstCacheMissReason  cause de rupture du cache au 1er appel ({@code messages_changed}…,
     *                              {@code none}, {@code pending}), {@code null} = non demandée
     * @param firstCacheMissedTokens tokens situés après la rupture, au 1er appel
     * @param thinkingReplayed      blocs de raisonnement envoyés au 1er appel
     * @param thinkingDropped       blocs écartés par le fournisseur au 1er appel
     * @param thinkingDroppedTotal  blocs écartés sur tous les appels du tour
     * @param lastResponseId        identifiant de la dernière réponse du tour : le 1er appel du tour
     *                              suivant du même fil s'y chaîne
     */
    public record Provider(String firstCacheMissReason, Long firstCacheMissedTokens, Integer thinkingReplayed,
            Integer thinkingDropped, Integer thinkingDroppedTotal, String lastResponseId) {
    }

    /**
     * <b>Le ruban du tour</b> (F-229 / SF-229-01) : des hachages et des nombres, jamais de contenu.
     * Chaque champ peut manquer ({@code null}) : la mesure est best-effort.
     *
     * @param fingerprint    empreinte JSON {@code [[hachage, taille], …]} de la dernière requête du tour
     * @param divergence     où et pourquoi le premier appel du tour a quitté le ruban du tour précédent
     * @param replayCutChars caractères retirés au stockage par rapport à ce qui a été envoyé (résultats
     *                       coupés, étapes abandonnées) : ce qu'ajouterait un rejeu intégral
     * @param ribbonReplayedTurns tours rejoués depuis leur ruban envoyé au 1er appel (F-276 / SF-276-02)
     * @param ribbonChars    taille du ruban envoyé rangé par ce tour, {@code null} s'il n'est pas rangé
     */
    public record Ribbon(String fingerprint, RibbonDivergence divergence, Long replayCutChars,
            Integer ribbonReplayedTurns, Long ribbonChars) {

        /** Forme d'avant F-276 / SF-276-02 : ni ruban rejoué ni ruban rangé connus. */
        public Ribbon(String fingerprint, RibbonDivergence divergence, Long replayCutChars) {
            this(fingerprint, divergence, replayCutChars, null, null);
        }
    }
}
