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
 * <b>Un retour à la seconde</b> (F-214 / SF-214-01) : une remarque attachée à un instant (ou une plage) d'une
 * version d'une vidéo, avec son statut.
 */
@Entity
@Table(name = "video_comments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoComment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "video_id", nullable = false, updatable = false)
    private UUID videoId;

    /** La version regardée quand le retour a été posé. */
    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Column(name = "t_start_ms", nullable = false, updatable = false)
    private long startMs;

    /** Fin de la plage, ou {@code null} pour un instant. */
    @Column(name = "t_end_ms", updatable = false)
    private Long endMs;

    /** L'image de l'instant de début (30 i/s). */
    @Column(name = "frame", nullable = false, updatable = false)
    private int frame;

    @Column(name = "text", nullable = false, length = 1000)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private VideoCommentStatus status;

    /** Motif donné par l'agent (surtout pour un rejet), ou {@code null}. */
    @Column(name = "resolution", length = 300)
    private String resolution;

    /** La version qui a traité (ou rejeté) le retour, ou {@code null}. */
    @Column(name = "resolved_version")
    private Integer resolvedVersion;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
