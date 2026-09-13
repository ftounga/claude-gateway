package fr.claudegateway.radar.analysis;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Un <b>lot d'échanges</b> dans la file d'analyse (F-101 / SF-101-01).
 *
 * <p>{@code payload} porte le texte brut remonté par la collecte. <b>Il ne survit pas à l'analyse</b> :
 * mis à {@code null} dans la transaction qui écrit les faits, ou au terme de la rétention (7 jours) —
 * des extraits, pas des archives (cadrage §4.6).</p>
 */
@Entity
@Table(name = "radar_analysis_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarAnalysisBatch {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "sync_id", nullable = false)
    private UUID syncId;

    @Column(name = "batch_key", nullable = false, updatable = false, length = RadarExchangeBatch.MAX_BATCH_KEY_LENGTH)
    private String batchKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RadarAnalysisBatchStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    @Column(name = "failure_code", length = 32)
    private String failureCode;

    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    @Column(name = "exchanges_count", nullable = false)
    private int exchangesCount;

    @Column(name = "messages_count", nullable = false)
    private int messagesCount;

    @Column(name = "retained_count", nullable = false)
    private int retainedCount;

    @Column(name = "subjects_attached", nullable = false)
    private int subjectsAttached;

    @Column(name = "subjects_created", nullable = false)
    private int subjectsCreated;

    @Column(name = "triage_input_tokens", nullable = false)
    private long triageInputTokens;

    @Column(name = "triage_output_tokens", nullable = false)
    private long triageOutputTokens;

    @Column(name = "extraction_input_tokens", nullable = false)
    private long extractionInputTokens;

    @Column(name = "extraction_output_tokens", nullable = false)
    private long extractionOutputTokens;

    @Column(name = "cache_read_tokens", nullable = false)
    private long cacheReadTokens;

    @Column(name = "cache_write_tokens", nullable = false)
    private long cacheWriteTokens;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "analyzed_at")
    private OffsetDateTime analyzedAt;

    @Column(name = "raw_deleted_at")
    private OffsetDateTime rawDeletedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Ajoute une consommation. */
    public void addTokens(RadarAnalysisTokens tokens) {
        if (tokens == null) {
            return;
        }
        triageInputTokens += tokens.triageInputTokens();
        triageOutputTokens += tokens.triageOutputTokens();
        extractionInputTokens += tokens.extractionInputTokens();
        extractionOutputTokens += tokens.extractionOutputTokens();
        cacheReadTokens += tokens.cacheReadTokens();
        cacheWriteTokens += tokens.cacheWriteTokens();
    }

    /** La consommation cumulée du lot. */
    public RadarAnalysisTokens tokens() {
        return new RadarAnalysisTokens(triageInputTokens, triageOutputTokens, extractionInputTokens,
                extractionOutputTokens, cacheReadTokens, cacheWriteTokens);
    }

    /** Efface le texte brut. */
    public void deleteRaw(OffsetDateTime now) {
        payload = null;
        if (rawDeletedAt == null) {
            rawDeletedAt = now;
        }
    }
}
