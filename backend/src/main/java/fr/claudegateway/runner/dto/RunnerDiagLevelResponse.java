package fr.claudegateway.runner.dto;

import fr.claudegateway.runner.diag.RunnerDiagControlService;

/**
 * Résultat d'une demande de réglage du niveau de diagnostic d'un poste (F-132 / SF-132-05).
 *
 * @param delivered la commande a été remise au runner (faux si le poste n'est joignable sur aucun pod)
 * @param level     le niveau demandé ({@code DEBUG})
 * @param minutes   la durée effective, en minutes (bornée)
 */
public record RunnerDiagLevelResponse(boolean delivered, String level, int minutes) {

    public static RunnerDiagLevelResponse from(RunnerDiagControlService.Outcome outcome) {
        return new RunnerDiagLevelResponse(outcome.delivered(), outcome.level(), outcome.minutes());
    }
}
