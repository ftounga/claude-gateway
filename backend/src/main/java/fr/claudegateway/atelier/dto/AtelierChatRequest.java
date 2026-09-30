package fr.claudegateway.atelier.dto;

import java.util.List;
import java.util.UUID;

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
 *
 * <p>{@code attachedDepositIds} (F-169 / SF-169-02) porte les identifiants des dépôts (renvoyés par
 * {@code POST /workspaces/{id}/deposit}) que ce message <b>joint explicitement</b>. Absent / vide ⇒
 * comportement historique (les dépôts non lus sont consommés par fenêtre temporelle, F-115) : un
 * client d'avant SF-169-02 ne régresse pas. Non vide ⇒ exactement ces dépôts sont attachés à ce
 * message (isolation {@code user_id} + {@code workspace_id} appliquée au service).</p>
 */
public record AtelierChatRequest(
        @NotBlank(message = "Le message est requis.")
        @Size(max = 32000, message = "Le message est trop long.")
        String message,

        AgentTurnMode mode,

        Boolean force,

        List<UUID> attachedDepositIds) {

    /** Forme historique sans mode — conservée pour les appelants (défaut {@code ACT} au service). */
    public AtelierChatRequest(String message) {
        this(message, null, null, null);
    }

    /** Forme d'avant la porte (F-161 / SF-161-01), conservée pour les appelants existants. */
    public AtelierChatRequest(String message, AgentTurnMode mode) {
        this(message, mode, null, null);
    }

    /** Forme d'avant les pièces jointes (F-169 / SF-169-02), conservée pour les appelants existants. */
    public AtelierChatRequest(String message, AgentTurnMode mode, Boolean force) {
        this(message, mode, force, null);
    }

    /** « Demander quand même » : absent ⇒ la porte s'applique. */
    public boolean forceOrDefault() {
        return Boolean.TRUE.equals(force);
    }

    /** Les dépôts joints à ce message, jamais {@code null} : absent ⇒ liste vide (fenêtre temporelle). */
    public List<UUID> attachedDepositIdsOrEmpty() {
        return attachedDepositIds == null ? List.of() : attachedDepositIds;
    }

    /** Le mode effectif du tour : {@link AgentTurnMode#ACT} quand aucun mode n'est fourni. */
    public AgentTurnMode modeOrDefault() {
        return mode == null ? AgentTurnMode.ACT : mode;
    }
}
