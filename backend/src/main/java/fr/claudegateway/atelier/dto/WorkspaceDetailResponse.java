package fr.claudegateway.atelier.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.atelier.Workspace;

/**
 * Vue détaillée d'un workspace : métadonnées + arborescence (chemins relatifs). N'expose aucune clé
 * de stockage brute.
 */
public record WorkspaceDetailResponse(
        UUID id, String name, int fileCount, List<String> files, OffsetDateTime createdAt) {

    public static WorkspaceDetailResponse from(Workspace workspace, List<String> files) {
        return new WorkspaceDetailResponse(
                workspace.getId(), workspace.getName(), files.size(), files, workspace.getCreatedAt());
    }
}
