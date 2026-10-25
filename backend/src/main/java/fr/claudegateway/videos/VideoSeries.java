package fr.claudegateway.videos;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** <b>Une série</b> (F-213) : les épisodes d'un même tutoriel, rangés sous un titre, dans un sujet. */
@Entity
@Table(name = "video_series")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoSeries {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "host_id", updatable = false)
    private UUID hostId;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "description", length = 300)
    private String description;

    /** La voix fixée pour toute la série (F-215 / SF-215-04), ou {@code null}. */
    @Column(name = "voice", length = 32)
    private String voice;

    /** Les consignes de ton de la série, ou {@code null}. */
    @Column(name = "voice_instructions", length = 1000)
    private String voiceInstructions;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
