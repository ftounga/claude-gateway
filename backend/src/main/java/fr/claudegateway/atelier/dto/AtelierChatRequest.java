package fr.claudegateway.atelier.dto;

import fr.claudegateway.agent.AgentTurnMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /api/workspaces/{id}/chat} (F-28 / SF-28-02).
 *
 * <p>{@code mode} (F-120 / SF-120-02) est <b>optionnel</b> : absent ⇒ {@link AgentTurnMode#ACT}
 * (comportement historique, panoplie complète). Une valeur d'enum inconnue est rejetée par la
 * désérialisation (400) plutôt qu'interprétée comme un mode par défaut.</p>
 */
public record AtelierChatRequest(
        @NotBlank(message = "Le message est requis.")
        @Size(max = 32000, message = "Le message est trop long.")
        String message,

        AgentTurnMode mode) {

    /** Forme historique sans mode — conservée pour les appelants (défaut {@code ACT} au service). */
    public AtelierChatRequest(String message) {
        this(message, null);
    }

    /** Le mode effectif du tour : {@link AgentTurnMode#ACT} quand aucun mode n'est fourni. */
    public AgentTurnMode modeOrDefault() {
        return mode == null ? AgentTurnMode.ACT : mode;
    }
}
