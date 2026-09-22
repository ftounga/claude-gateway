package fr.claudegateway.presentations.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.presentations.Presentation;

/**
 * Une présentation telle que l'écran la lit (F-129 / SF-129-02). {@code slideCount} est {@code null}
 * tant que le rendu par slides (SF-129-03) n'a pas été produit.
 */
public record PresentationResponse(UUID id, String title, String description, String space,
        UUID hostId, UUID workspaceId, long pptxBytes, Integer slideCount, OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static PresentationResponse of(Presentation p) {
        return new PresentationResponse(p.getId(), p.getTitle(), p.getDescription(), p.getSpace().name(),
                p.getHostId(), p.getWorkspaceId(), p.getPptxBytes(), p.getSlideCount(), p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
