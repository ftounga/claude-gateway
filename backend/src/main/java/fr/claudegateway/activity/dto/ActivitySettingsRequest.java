package fr.claudegateway.activity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Requête de réglage du mois de départ du cumul (F-124 / SF-124-01). Le format {@code YYYY-MM} est
 * validé ici <b>et</b> au service (défense en profondeur).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivitySettingsRequest(
        @NotBlank
        @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$",
                message = "Le mois de départ doit être au format YYYY-MM.")
        String startMonth) {
}
