package fr.claudegateway.chat.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /api/chat}.
 *
 * @param conversationId conversation existante (null => nouvelle conversation)
 * @param message        message utilisateur (obligatoire, non vide)
 * @param model          modèle souhaité (null => modèle par défaut)
 * @param attachmentIds  identifiants de fichiers téléversés à rattacher (null/vide => aucun ; max 10)
 * @param libraryDocumentIds identifiants de documents de la bibliothèque personnelle (F-08) dont le
 *                       texte OCR est injecté comme contexte dans l'appel fournisseur (null/vide => aucun ; max 10)
 */
public record ChatRequest(
        UUID conversationId,
        @NotBlank(message = "Le message est requis.")
        @Size(max = 32000, message = "Le message est trop long.")
        String message,
        @Size(max = 64, message = "Modèle invalide.")
        String model,
        @Size(max = 10, message = "Trop de pièces jointes.")
        List<UUID> attachmentIds,
        @Size(max = 10, message = "Trop de documents de bibliothèque.")
        List<UUID> libraryDocumentIds) {
}
