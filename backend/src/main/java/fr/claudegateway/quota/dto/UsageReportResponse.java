package fr.claudegateway.quota.dto;

import java.math.BigDecimal;
import java.util.List;

import fr.claudegateway.quota.UsageReport;

/**
 * Réponse de {@code GET /api/usage/report} (F-16) : historique mensuel de consommation et coût
 * estimé de l'utilisateur courant, avec totaux sur la fenêtre. Aucune donnée sensible (ni Stripe,
 * ni clé).
 *
 * @param currency            devise du coût estimé
 * @param periods             périodes, triées de la plus récente à la plus ancienne
 * @param totalInputTokens    somme des tokens d'entrée sur la fenêtre
 * @param totalOutputTokens   somme des tokens de sortie sur la fenêtre
 * @param totalTokens         somme des tokens sur la fenêtre
 * @param totalEstimatedCost  somme des coûts estimés sur la fenêtre
 */
public record UsageReportResponse(
        String currency,
        List<UsagePeriodResponse> periods,
        long totalInputTokens,
        long totalOutputTokens,
        long totalTokens,
        BigDecimal totalEstimatedCost) {

    /** Projette un rapport métier en réponse d'API. */
    public static UsageReportResponse from(UsageReport report) {
        return new UsageReportResponse(
                report.currency(),
                report.periods().stream().map(UsagePeriodResponse::from).toList(),
                report.totalInputTokens(),
                report.totalOutputTokens(),
                report.totalTokens(),
                report.totalEstimatedCost());
    }
}
