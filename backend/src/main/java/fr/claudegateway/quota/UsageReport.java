package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.util.List;

/**
 * Rapport d'usage &amp; coût d'un utilisateur (F-16) : historique mensuel et totaux sur la fenêtre
 * retournée. Objet métier interne renvoyé par {@link UsageReportService} et projeté en DTO par le
 * controller. Aucune donnée sensible.
 *
 * @param currency            devise du coût estimé
 * @param periods             périodes, triées de la plus récente à la plus ancienne
 * @param totalInputTokens    somme des tokens d'entrée sur la fenêtre
 * @param totalOutputTokens   somme des tokens de sortie sur la fenêtre
 * @param totalTokens         somme des tokens (entrée + sortie) sur la fenêtre
 * @param totalEstimatedCost  somme des coûts estimés sur la fenêtre
 */
public record UsageReport(
        String currency,
        List<UsagePeriod> periods,
        long totalInputTokens,
        long totalOutputTokens,
        long totalTokens,
        BigDecimal totalEstimatedCost) {
}
