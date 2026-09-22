package fr.claudegateway.images.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.images.GeneratedImage;

/**
 * Une image générée telle que l'écran la lit (F-142 / SF-142-04). {@code status} porte l'état lisible de
 * la génération ; {@code imageBytes} / {@code costEur} restent {@code null} tant que non READY.
 */
public record GeneratedImageResponse(UUID id, String prompt, String size, String status,
        String space, UUID hostId, UUID workspaceId, Long imageBytes, BigDecimal costEur, String error,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    public static GeneratedImageResponse of(GeneratedImage i) {
        return new GeneratedImageResponse(i.getId(), i.getPrompt(), i.getSize(), i.getStatus().name(),
                i.getSpace().name(), i.getHostId(), i.getWorkspaceId(), i.getImageBytes(), i.getCostEur(),
                i.getError(), i.getCreatedAt(), i.getUpdatedAt());
    }
}
