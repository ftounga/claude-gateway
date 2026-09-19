package fr.claudegateway.runner.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Demande de passage d'un poste en {@code DEBUG} le temps d'un diagnostic (F-132 / SF-132-05).
 *
 * @param minutes durée du DEBUG ; facultative (défaut 10), bornée [1..60] côté service
 */
public record RunnerDiagLevelRequest(
        @Min(1) @Max(60) Integer minutes) {
}
