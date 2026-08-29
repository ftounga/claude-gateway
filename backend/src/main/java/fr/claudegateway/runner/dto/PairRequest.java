package fr.claudegateway.runner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /runner/pair} : le code d'appairage et un libellé facultatif pour le jeton.
 */
public record PairRequest(
        @NotBlank @Size(max = 8) String code,
        @Size(max = 100) String label) {
}
