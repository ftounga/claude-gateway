package fr.claudegateway.atelier.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceSource;

/**
 * Vue résumée d'un workspace (liste). N'expose aucune clé de stockage interne.
 *
 * <p>La {@code source} figure dès la liste (F-31 / SF-31-02) : un projet Git et un projet d'archive
 * n'offrent pas les mêmes gestes, et l'écran doit pouvoir le montrer sans charger le détail. Depuis
 * F-38 (SF-38-05), la {@code executionTarget} y figure pour la même raison : un projet qui s'exécute
 * sur la machine de l'utilisateur se signale dès la liste.</p>
 */
public record WorkspaceSummaryResponse(
        UUID id, String name, OffsetDateTime createdAt, WorkspaceSource source, String gitRepo,
        WorkspaceExecutionTarget executionTarget) {

    public static WorkspaceSummaryResponse from(Workspace workspace) {
        String fullName = workspace.getGitOwner() == null || workspace.getGitRepo() == null
                ? null
                : workspace.getGitOwner() + "/" + workspace.getGitRepo();
        return new WorkspaceSummaryResponse(workspace.getId(), workspace.getName(),
                workspace.getCreatedAt(), workspace.sourceOrDefault(), fullName,
                workspace.executionTargetOrDefault());
    }
}
