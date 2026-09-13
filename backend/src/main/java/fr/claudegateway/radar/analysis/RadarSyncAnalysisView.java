package fr.claudegateway.radar.analysis;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Où en est l'analyse d'une synchro, et ce qu'elle a coûté (F-101 / SF-101-01, SF-101-05). <b>Jamais
 * le texte.</b>
 *
 * @param batches                  nombre de lots par statut (tous les statuts sont présents, à 0 au besoin)
 * @param exchanges                échanges reçus
 * @param messages                 messages reçus
 * @param retained                 échanges retenus par le tri
 * @param subjectsAttached         rattachements à un sujet existant
 * @param subjectsCreated          sujets créés
 * @param tokens                   consommation de l'analyse, par passe et par nature
 * @param costUsd                  coût <b>estimé aux tarifs configurés</b> de cette consommation
 * @param stoppedOnReserve         vrai si au moins un lot est reporté pour réserve épuisée
 * @param unreadBatches            lots jamais analysés : échoués ou expirés (échecs de couverture)
 * @param attachmentCorrections    fusions et séparations de l'utilisateur, non annulées, faites entre le
 *                                 début de cette synchro et le début de la suivante
 * @param attachmentCorrectionRate {@code attachmentCorrections ÷ (rattachés + créés)}, 3 décimales ;
 *                                 {@code null} si la synchro n'a rien rattaché
 */
public record RadarSyncAnalysisView(Map<RadarAnalysisBatchStatus, Long> batches, long exchanges,
        long messages, long retained, long subjectsAttached, long subjectsCreated, RadarAnalysisTokens tokens,
        BigDecimal costUsd, boolean stoppedOnReserve, long unreadBatches, long attachmentCorrections,
        Double attachmentCorrectionRate) {
}
