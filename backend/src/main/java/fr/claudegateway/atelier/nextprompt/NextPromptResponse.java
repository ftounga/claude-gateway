package fr.claudegateway.atelier.nextprompt;

import java.util.UUID;

/**
 * Réponse de {@code POST /workspaces/{id}/next-prompt} (F-144 / SF-144-02).
 *
 * @param suggestion la suite prédite, ou {@code null} quand il n'y a rien à proposer — l'écran
 *                   retombe alors sur les puces SF-144-01
 * @param messageId  le dernier message de l'agent auquel la suite répond, ou {@code null} sans tour
 *                   terminé
 */
public record NextPromptResponse(String suggestion, UUID messageId) {
}
