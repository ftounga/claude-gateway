package fr.claudegateway.atelier.dto;

import java.util.List;
import java.util.UUID;

/**
 * Réponse de {@code POST /api/workspaces/{id}/chat} (F-28 / SF-28-02).
 *
 * @param reply     réponse finale de Claude
 * @param actions   fichiers lus/écrits par l'agent pendant le tour (pour l'UI)
 * @param messageId identifiant du message assistant persisté
 */
public record AtelierChatResponse(String reply, List<AtelierAction> actions, UUID messageId) {

    /** Action de fichier réalisée par l'agent : {@code type} = {@code read} ou {@code write}. */
    public record AtelierAction(String type, String path) {
    }
}
