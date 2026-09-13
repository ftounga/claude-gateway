package fr.claudegateway.radar.analysis;

import java.util.UUID;

import fr.claudegateway.radar.RadarScope;

/**
 * Ce qui analyse un lot (F-101) : le tri puis l'extraction, branchés sur la file en SF-101-03.
 *
 * <p>La file appelle {@link #analyze} <b>hors transaction</b> — les appels au modèle sont longs — puis
 * applique l'issue dans une seule transaction : les écritures rendues par l'analyseur, et l'effacement
 * du texte brut. Tant qu'aucune implémentation n'est déclarée, la file ne prend aucun lot.</p>
 */
public interface RadarBatchAnalyzer {

    /**
     * Analyse un lot.
     *
     * @param scope   le poste, déjà résolu par la file
     * @param syncId  la synchro du lot
     * @param batchId le lot
     * @param batch   le lot normalisé
     * @return l'issue ; une exception vaut {@code RETRY}
     */
    RadarAnalysisOutcome analyze(RadarScope scope, UUID syncId, UUID batchId, RadarExchangeBatch batch);
}
