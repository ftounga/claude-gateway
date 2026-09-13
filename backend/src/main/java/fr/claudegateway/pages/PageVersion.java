package fr.claudegateway.pages;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * <b>Une version conservée d'une page</b> (F-109 / SF-109-01). {@link #sizeBytes} compte le HTML
 * <b>et</b> ses pièces jointes : la somme de ces lignes, par compte, porte le quota de stockage.
 */
@Entity
@Table(name = "page_versions")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageVersion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "page_id", nullable = false, updatable = false)
    private UUID pageId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "attachment_count", nullable = false, updatable = false)
    private int attachmentCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
