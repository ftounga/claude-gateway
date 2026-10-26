package fr.claudegateway.videos;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

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
 * <b>Un devis de vidéo</b> (F-216 / SF-216-01) : ce qui a été chiffré, la fourchette par poste, et s'il est encore
 * ouvert dans son sujet. Aucun contenu (ni narration, ni titre de vidéo) : des nombres, et le titre de série annoncé.
 */
@Entity
@Table(name = "video_quotes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoQuote {

    /** Statut d'un devis : ouvert dans son sujet, ou clos. */
    public enum Status {
        OPEN, CLOSED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10, updatable = false)
    private VideoQuoteKind kind;

    @Column(name = "series_title", length = 120, updatable = false)
    private String seriesTitle;

    /** La vidéo retouchée (devis {@code RETOUCHE}), ou {@code null}. Sans clé étrangère : un devis survit à la vidéo. */
    @Column(name = "video_id", updatable = false)
    private UUID videoId;

    @Column(name = "episodes", nullable = false, updatable = false)
    private int episodes;

    @Column(name = "episode_duration_s", nullable = false, updatable = false)
    private int episodeDurationS;

    @Column(name = "narration_chars", nullable = false, updatable = false)
    private long narrationChars;

    @Column(name = "retouches", nullable = false, updatable = false)
    private int retouches;

    @Column(name = "voice_low_usd", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal voiceLowUsd;

    @Column(name = "voice_high_usd", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal voiceHighUsd;

    @Column(name = "agent_low_usd", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal agentLowUsd;

    @Column(name = "agent_high_usd", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal agentHighUsd;

    @Column(name = "low_usd", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal lowUsd;

    @Column(name = "high_usd", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal highUsd;

    @Column(name = "turn_cost_usd", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal turnCostUsd;

    @Column(name = "turn_cost_measured", nullable = false, updatable = false)
    private boolean turnCostMeasured;

    @Column(name = "render_minutes", nullable = false, updatable = false)
    private int renderMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    /** Ce qui a été dépensé pendant que le devis était ouvert (SF-216-02) — plans abandonnés compris. */
    @Builder.Default
    @Column(name = "spent_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal spentUsd = BigDecimal.ZERO;

    /** La part de cette dépense pas encore rattachée à une version publiée (SF-216-02). */
    @Builder.Default
    @Column(name = "unassigned_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal unassignedUsd = BigDecimal.ZERO;

    /** Dernière imputation ou publication (SF-216-02) : au-delà du délai d'inactivité, le devis est clos. */
    @Column(name = "last_activity_at")
    private OffsetDateTime lastActivityAt;
}
