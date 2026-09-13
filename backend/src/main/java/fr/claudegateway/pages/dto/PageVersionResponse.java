package fr.claudegateway.pages.dto;

import java.time.OffsetDateTime;

import fr.claudegateway.pages.PageVersion;

/** Une version conservée d'une page, telle que l'écran la liste (F-109 / SF-109-04). */
public record PageVersionResponse(int version, long sizeBytes, int attachmentCount, OffsetDateTime createdAt) {

    public static PageVersionResponse of(PageVersion version) {
        return new PageVersionResponse(version.getVersion(), version.getSizeBytes(), version.getAttachmentCount(),
                version.getCreatedAt());
    }
}
