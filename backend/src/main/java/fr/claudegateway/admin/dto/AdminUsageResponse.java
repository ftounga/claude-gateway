package fr.claudegateway.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.admin.AdminUsage;

/**
 * Réponse de {@code GET /admin/usage} (F-61 / SF-61-03) : qui consomme, combien, à quel coût, et
 * comment cela évolue.
 *
 * <p>Des volumes et des coûts, <b>jamais</b> des contenus — et pas davantage de nom de projet ou de
 * poste. Ce que l'administrateur voit d'un compte : son identité, son plan, ses chiffres.</p>
 */
public record AdminUsageResponse(
        String currency,
        LocalDate from,
        LocalDate to,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        BigDecimal estimatedCost,
        List<UserUsageResponse> users) {

    /** Un compte et sa consommation sur la fenêtre. */
    public record UserUsageResponse(
            UUID userId,
            String email,
            String role,
            String planCode,
            String subscriptionStatus,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            BigDecimal estimatedCost,
            BigDecimal share,
            List<MonthUsageResponse> periods) {
    }

    /** Un mois de consommation. */
    public record MonthUsageResponse(
            LocalDate periodStart,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            BigDecimal estimatedCost) {
    }

    /** Projette l'objet métier en réponse d'API. */
    public static AdminUsageResponse from(AdminUsage usage) {
        List<UserUsageResponse> users = usage.users().stream()
                .map(user -> new UserUsageResponse(
                        user.userId(),
                        user.email(),
                        user.role(),
                        user.planCode(),
                        user.subscriptionStatus(),
                        user.inputTokens(),
                        user.outputTokens(),
                        user.totalTokens(),
                        user.estimatedCost(),
                        user.share(),
                        user.periods().stream()
                                .map(period -> new MonthUsageResponse(
                                        period.periodStart(),
                                        period.inputTokens(),
                                        period.outputTokens(),
                                        period.totalTokens(),
                                        period.estimatedCost()))
                                .toList()))
                .toList();
        return new AdminUsageResponse(
                usage.currency(),
                usage.from(),
                usage.to(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                usage.estimatedCost(),
                users);
    }
}
