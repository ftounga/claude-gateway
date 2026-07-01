package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ligne d'historique du rapport d'usage (F-16) : consommation et coût estimé d'un utilisateur pour
 * un mois calendaire. Objet métier interne construit par {@link UsageReportService} et projeté en
 * DTO par le controller.
 *
 * @param periodStart    premier jour du mois (UTC)
 * @param periodEnd      premier jour du mois suivant (borne exclusive)
 * @param inputTokens    tokens d'entrée consommés sur la période
 * @param outputTokens   tokens de sortie consommés sur la période
 * @param totalTokens    total (entrée + sortie)
 * @param estimatedCost  coût estimé au tarif configuré (devise du rapport)
 * @param current        {@code true} si la période est le mois calendaire UTC courant
 */
public record UsagePeriod(
        LocalDate periodStart,
        LocalDate periodEnd,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        BigDecimal estimatedCost,
        boolean current) {
}
