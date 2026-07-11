package fr.claudegateway.atelier.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.atelier.Workspace;

/** Vue résumée d'un workspace (liste). N'expose aucune clé de stockage interne. */
public record WorkspaceSummaryResponse(UUID id, String name, OffsetDateTime createdAt) {

    public static WorkspaceSummaryResponse from(Workspace workspace) {
        return new WorkspaceSummaryResponse(workspace.getId(), workspace.getName(), workspace.getCreatedAt());
    }
}
