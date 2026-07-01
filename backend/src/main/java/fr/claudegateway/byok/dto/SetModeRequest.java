package fr.claudegateway.byok.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Requête de bascule du mode fournisseur (F-03 / SF-03-03).
 *
 * @param mode {@code HOSTED} (clé plateforme) ou {@code BYOK} (clé personnelle)
 */
public record SetModeRequest(
        @NotBlank
        @Pattern(regexp = "HOSTED|BYOK", message = "mode doit être HOSTED ou BYOK")
        String mode) {
}
