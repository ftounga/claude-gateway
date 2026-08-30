package fr.claudegateway.runner.dto;

import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.runner.RunnerKillSwitchService.KillResult;

/**
 * Résultat d'un coupe-circuit runner (F-38 / SF-38-08).
 *
 * @param revokedTokens   jetons encore valides qui ont été révoqués (0 si la liaison était déjà coupée)
 * @param disconnected    vrai si une socket vivante a été fermée sur ce nœud
 * @param executionTarget cible d'exécution du projet après le geste — ramenée à {@code SANDBOX}
 */
public record RunnerKillResponse(int revokedTokens, boolean disconnected,
        WorkspaceExecutionTarget executionTarget) {

    public static RunnerKillResponse from(KillResult result) {
        return new RunnerKillResponse(result.revokedTokens(), result.disconnected(),
                result.executionTarget());
    }
}
