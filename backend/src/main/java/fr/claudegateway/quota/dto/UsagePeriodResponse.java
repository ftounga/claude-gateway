package fr.claudegateway.quota.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import fr.claudegateway.quota.UsagePeriod;

/**
 * Ligne d'historique de {@code GET /api/usage/report} (F-16) : consommation et coût estimé d'un
 * mois. Aucune donnée sensible.
 *
 * @param periodStart    premier jour du mois (UTC, ISO {@code YYYY-MM-DD})
 * @param periodEnd      premier jour du mois suivant (borne exclusive)
 * @param inputTokens    tokens d'entrée consommés
 * @param outputTokens   tokens de sortie consommés
 * @param totalTokens    total (entrée + sortie)
 * @param estimatedCost  coût estimé (devise du rapport)
 * @param current        {@code true} pour le mois calendaire UTC courant
 */
public record UsagePeriodResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        BigDecimal estimatedCost,
        boolean current) {

    /** Projette une période métier en réponse d'API. */
    public static UsagePeriodResponse from(UsagePeriod period) {
        return new UsagePeriodResponse(
                period.periodStart(),
                period.periodEnd(),
                period.inputTokens(),
                period.outputTokens(),
                period.totalTokens(),
                period.estimatedCost(),
                period.current());
    }
}
