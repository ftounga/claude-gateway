package fr.claudegateway.atelier.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.quota.TurnCostView;

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
        return from(message, null);
    }

    /**
     * Même vue, en décidant du <b>coût</b> (F-133 / SF-133-02).
     *
     * <p>Le relevé est stocké avec le coût du tour <b>en dollars</b>. Ce champ ne doit jamais
     * partir tel quel : il est remplacé par un montant en euros pour l'administrateur, et
     * <b>retiré</b> pour tout le monde d'autre. Le masquer côté écran ne suffirait pas — il
     * resterait lisible dans le flux réseau.</p>
     *
     * @param costView décideur d'affichage, ou {@code null} pour retirer le coût sans condition
     */
    public static AtelierMessageResponse from(AtelierMessage message, TurnCostView costView) {
        return new AtelierMessageResponse(message.getId(), message.getRole(), message.getContent(),
                message.getCreatedAt(),
                withCost(parseTranscript(message.getTerminalJson()), costView));
    }

    /**
     * Remplace {@code costUsd} par {@code costEur} quand l'appelant y a droit, et le retire sinon.
     * Un relevé sans coût (tous ceux d'avant F-133) traverse inchangé.
     */
    private static JsonNode withCost(JsonNode transcript, TurnCostView costView) {
        if (transcript == null || !transcript.isObject() || !transcript.hasNonNull("costUsd")) {
            return transcript;
        }
        ObjectNode node = (ObjectNode) transcript;
        String label = costView == null ? null : costView.labelFor(node.get("costUsd").decimalValue());
        node.remove("costUsd");
        if (label != null) {
            node.put("costEur", label);
        }
        return node;
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
