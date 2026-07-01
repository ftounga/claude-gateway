package fr.claudegateway.ask.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /api/ask} (F-07). Contrat figé (importé tel quel par SF-07-02 frontend).
 *
 * @param question question de l'utilisateur (obligatoire, non vide, max 8000 caractères)
 * @param model    modèle souhaité (null => modèle par défaut ; hors liste blanche => 400)
 * @param topK     nombre de chunks de contexte souhaités (null => défaut ; borné [1,20] par le service)
 */
public record AskRequest(
        @NotBlank(message = "La question est requise.")
        @Size(max = 8000, message = "La question est trop longue.")
        String question,
        @Size(max = 64, message = "Modèle invalide.")
        String model,
        Integer topK) {
}
