package fr.claudegateway.help.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Question posée au chatbot d'aide produit (F-54 / SF-54-01).
 *
 * @param message question en langue naturelle, non vide et bornée à 500 caractères
 */
public record HelpChatRequest(
        @NotBlank(message = "La question ne peut pas être vide.")
        @Size(max = 500, message = "La question ne peut pas dépasser 500 caractères.")
        String message) {
}
