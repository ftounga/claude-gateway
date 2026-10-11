package fr.claudegateway.atelier.fil;

import java.util.List;
import java.util.UUID;

import fr.claudegateway.atelier.live.TurnDigest;

/**
 * Réponse de {@code GET /workspaces/{id}/fil} (F-191 / SF-191-01) — contrat figé de la mini-spec,
 * consommé par la bande des tâches (SF-191-02) et le bandeau de fin (SF-191-03).
 *
 * @param hostId     poste du terminal, {@code null} pour un terminal sans poste
 * @param serverTime instant serveur (ms epoch) : l'écran calcule les durées sans dépendre de son horloge
 */
public record PosteTurnsResponse(UUID hostId, long serverTime, List<Item> turns) {

    /**
     * Une tâche du poste.
     *
     * @param root  vrai pour le terminal du poste (la racine)
     * @param here  vrai pour le terminal qui pose la question
     * @param state {@code waiting}, {@code running}, {@code done}, {@code failed} ou {@code interrupted}
     */
    public record Item(UUID turnId, UUID workspaceId, String workspaceName, boolean root, boolean here,
            String state, String waiting, String demand, long startedAt, Long endedAt, int planDone,
            int planTotal, String planStep, String tool, List<TurnDigest.SubAgent> subagents) {
    }
}
