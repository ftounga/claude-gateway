package fr.claudegateway.atelier.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.AtelierMessage;

/**
 * Vue d'un message Atelier exposée au client (F-28 / SF-28-02).
 *
 * <p>{@code terminal} porte la transcription d'un tour du mode Terminal (F-30 SF-30-09) : commandes,
 * sorties et coût. {@code null} pour les tours du mode Assistant — champ <b>additif</b>, un client
 * qui l'ignore se comporte comme avant.</p>
 */
public record AtelierMessageResponse(UUID id, String role, String content, OffsetDateTime createdAt,
        JsonNode terminal) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AtelierMessageResponse from(AtelierMessage message) {
        return new AtelierMessageResponse(message.getId(), message.getRole(), message.getContent(),
                message.getCreatedAt(), parseTranscript(message.getTerminalJson()));
    }

    /**
     * Relit la transcription stockée. Une donnée illisible (ancienne, tronquée) rend le tour
     * <b>sans</b> transcription plutôt que de casser tout l'historique : un défaut d'affichage ne doit
     * jamais empêcher de relire sa conversation.
     */
    private static JsonNode parseTranscript(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }
}
