package fr.claudegateway.activity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Le message de CRA en langage naturel (F-124 / SF-124-03). Le modèle en extrait
 * {@code {poste → jours, mois}} ; la Gateway rapproche, valide et persiste.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CraRequest(
        @NotBlank(message = "Le message de CRA est requis.")
        @Size(max = 4000, message = "Le message de CRA est trop long.")
        String message) {
}
