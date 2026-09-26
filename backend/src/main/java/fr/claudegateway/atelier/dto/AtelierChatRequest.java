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
 *
 * <p>{@code force} (F-161 / SF-161-01) est le « demander quand même » : il passe outre la porte du
 * runner. Absent ⇒ {@code false}, donc la porte s'applique. C'est l'utilisateur qui décide, parce
 * que lui seul sait si sa question touche la machine.</p>
 */
public record AtelierChatRequest(
        @NotBlank(message = "Le message est requis.")
        @Size(max = 32000, message = "Le message est trop long.")
        String message,

        AgentTurnMode mode,

        Boolean force) {

    /** Forme historique sans mode — conservée pour les appelants (défaut {@code ACT} au service). */
    public AtelierChatRequest(String message) {
        this(message, null, null);
    }

    /** Forme d'avant la porte (F-161 / SF-161-01), conservée pour les appelants existants. */
    public AtelierChatRequest(String message, AgentTurnMode mode) {
        this(message, mode, null);
    }

    /** « Demander quand même » : absent ⇒ la porte s'applique. */
    public boolean forceOrDefault() {
        return Boolean.TRUE.equals(force);
    }

    /** Le mode effectif du tour : {@link AgentTurnMode#ACT} quand aucun mode n'est fourni. */
    public AgentTurnMode modeOrDefault() {
        return mode == null ? AgentTurnMode.ACT : mode;
    }
}
