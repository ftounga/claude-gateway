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

    /** Le coût réel cumulé de ses versions (F-216 / SF-216-02), ou {@code null} : coût non mesuré. */
    @Column(name = "cost_usd", precision = 12, scale = 6)
    private java.math.BigDecimal costUsd;

    /** L'estimation cumulée des devis qui l'ont touchée (F-216 / SF-216-02), ou {@code null}. */
    @Column(name = "estimate_low_usd", precision = 10, scale = 4)
    private java.math.BigDecimal estimateLowUsd;

    @Column(name = "estimate_high_usd", precision = 10, scale = 4)
    private java.math.BigDecimal estimateHighUsd;

    /** Le dernier devis dont l'estimation a été ajoutée (F-216 / SF-216-02) : une seule fois par devis. */
    @Column(name = "last_quote_id")
    private UUID lastQuoteId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
