package fr.claudegateway.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code PATCH /api/conversations/{id}} : nouveau titre.
 */
public record RenameConversationRequest(
        @NotBlank(message = "Le titre est requis.")
        @Size(max = 200, message = "Le titre est trop long.")
        String title) {
}
