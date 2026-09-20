package fr.claudegateway.quota.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

/**
 * Ce que l'administrateur envoie pour poser un budget hebdomadaire (F-133 / SF-133-04).
 *
 * @param amountEur montant hebdomadaire en euros. Zéro est accepté — « ce client ne doit rien
 *                  coûter » est une consigne légitime ; négatif est refusé
 */
public record CostBudgetRequest(@NotNull BigDecimal amountEur) {
}
