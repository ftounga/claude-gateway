package fr.claudegateway.videos;

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
import lombok.Setter;

/** <b>Une version d'une vidéo</b> (F-213 / SF-213-01) : l'objet S3, sa taille, son type, ses chapitres. */
@Entity
@Table(name = "video_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoVersion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "video_id", nullable = false, updatable = false)
    private UUID videoId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Column(name = "storage_key", nullable = false, length = 300, updatable = false)
    private String storageKey;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "mime", nullable = false, length = 40, updatable = false)
    private String mime;

    /** Les chapitres, en JSON ({@code [{"t":0,"titre":"…"}]}), ou {@code null}. */
    @Column(name = "chapters_json", length = 16000)
    private String chaptersJson;

    /** La fiche des sources (F-215 / SF-215-04), en JSON, ou {@code null}. */
    @Column(name = "sources_json", length = 16000)
    private String sourcesJson;

    @Column(name = "poster_key", length = 300)
    private String posterKey;

    @Column(name = "poster_mime", length = 40)
    private String posterMime;

    /** Le libellé d'une piste audio (« Voix 1 — grave, posée »), ou {@code null}. */
    @Column(name = "label", length = 80)
    private String label;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
