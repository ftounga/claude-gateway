package fr.claudegateway.pages.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.pages.Page;

/**
 * Une page telle que l'écran la lit (F-109).
 *
 * @param viewUrl adresse de lecture de la version courante pour une {@code iframe} : un ticket court,
 *                jamais le jeton de l'application
 */
public record PageResponse(UUID id, String title, String description, String space, UUID hostId,
        UUID workspaceId, int currentVersion, OffsetDateTime createdAt, OffsetDateTime updatedAt,
        String viewUrl) {

    public static PageResponse of(Page page, String viewUrl) {
        return new PageResponse(page.getId(), page.getTitle(), page.getDescription(), page.getSpace().name(),
                page.getHostId(), page.getWorkspaceId(), page.getCurrentVersion(), page.getCreatedAt(),
                page.getUpdatedAt(), viewUrl);
    }
}
