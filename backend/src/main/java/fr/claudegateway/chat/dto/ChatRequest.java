package fr.claudegateway.chat.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /api/chat}.
 *
 * @param conversationId conversation existante (null => nouvelle conversation)
 * @param message        message utilisateur (obligatoire, non vide)
 * @param model          modèle souhaité (null => modèle par défaut)
 */
public record ChatRequest(
        UUID conversationId,
        @NotBlank(message = "Le message est requis.")
        @Size(max = 32000, message = "Le message est trop long.")
        String message,
        @Size(max = 64, message = "Modèle invalide.")
        String model) {
}
