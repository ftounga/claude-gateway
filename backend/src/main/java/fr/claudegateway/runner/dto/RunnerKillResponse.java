package fr.claudegateway.runner.dto;

import fr.claudegateway.runner.RunnerKillSwitchService.KillResult;

/**
 * Résultat d'un coupe-circuit runner (F-38 / SF-38-08, étendu au <b>poste</b> par F-48 / SF-48-01).
 *
 * @param revokedTokens      jetons encore valides qui ont été révoqués (0 si la liaison était déjà coupée)
 * @param disconnected       vrai si une socket vivante a été fermée sur ce nœud
 * @param workspacesReturned projets du poste ramenés à la cible {@code SANDBOX}
 */
public record RunnerKillResponse(int revokedTokens, boolean disconnected, int workspacesReturned) {

    public static RunnerKillResponse from(KillResult result) {
        return new RunnerKillResponse(result.revokedTokens(), result.disconnected(),
                result.workspacesReturned());
    }
}
