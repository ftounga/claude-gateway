package fr.claudegateway.atelier.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Corps de {@code POST /api/workspaces/{id}/agent/stream} (F-28 / SF-28-10). */
public record AtelierAgentRequest(
        @NotBlank(message = "Le message est requis.")
        @Size(max = 32000, message = "Le message est trop long.")
        String message) {
}
