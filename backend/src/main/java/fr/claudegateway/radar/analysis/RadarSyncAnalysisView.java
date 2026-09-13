package fr.claudegateway.radar.analysis;

import java.util.Map;

/**
 * Où en est l'analyse d'une synchro (F-101 / SF-101-01) : ses lots par statut, ce qu'ils portaient et
 * ce qu'ils ont consommé. <b>Jamais le texte.</b>
 *
 * @param batches          nombre de lots par statut (tous les statuts sont présents, à 0 au besoin)
 * @param exchanges        échanges reçus
 * @param messages         messages reçus
 * @param retained         échanges retenus par le tri
 * @param subjectsAttached rattachements à un sujet existant
 * @param subjectsCreated  sujets créés
 * @param tokens           consommation de l'analyse, par passe et par nature
 */
public record RadarSyncAnalysisView(Map<RadarAnalysisBatchStatus, Long> batches, long exchanges,
        long messages, long retained, long subjectsAttached, long subjectsCreated, RadarAnalysisTokens tokens) {
}
