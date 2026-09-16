package fr.claudegateway.activity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Requête de réglage du TJM d'un poste (F-124 / SF-124-01) : le montant en centimes d'euro HT.
 * Les bornes sont validées ici <b>et</b> au service (défense en profondeur).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PosteRateRequest(
        @NotNull(message = "Le TJM est requis.")
        @Min(value = 0, message = "Le TJM ne peut pas être négatif.")
        @Max(value = 100_000_000L, message = "Le TJM dépasse la borne autorisée.")
        Long dailyRateCents) {
}
