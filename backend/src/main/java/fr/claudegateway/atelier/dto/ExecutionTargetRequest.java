package fr.claudegateway.atelier.dto;

import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import jakarta.validation.constraints.NotNull;

/**
 * Demande de bascule de la cible d'exécution d'un projet (F-38 / SF-38-05).
 *
 * <p>Le champ est un <b>enum</b> : une valeur inconnue est refusée par la désérialisation (400), sans
 * qu'aucun code métier n'ait à connaître la liste des cibles valides.</p>
 *
 * @param executionTarget {@code SANDBOX} ou {@code RUNNER}
 */
public record ExecutionTargetRequest(@NotNull WorkspaceExecutionTarget executionTarget) {
}
