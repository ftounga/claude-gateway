package fr.claudegateway.videos;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Une vidéo — ou un son — publié par l'agent</b> (F-213 / SF-213-01). Les octets vivent en S3, par
 * version ({@link VideoVersion}) ; la ligne porte ce qui se liste : titre, série, rang, durée.
 */
@Entity
@Table(name = "videos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Video {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "host_id", updatable = false)
    private UUID hostId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 8, updatable = false)
    private VideoKind kind;

    @Column(name = "series_id")
    private UUID seriesId;

    @Column(name = "episode")
    private Integer episode;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "description", length = 300)
    private String description;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    /** Durée en secondes : annoncée par l'agent, corrigée par la lecture (métadonnées du conteneur). */
    @Column(name = "duration_s")
    private Integer durationS;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
