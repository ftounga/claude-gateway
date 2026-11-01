package fr.claudegateway.jarvis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Une journée de Jarvis pour un compte</b> (F-231 / SF-231-01, mesure SF-231-05) : ce que sa voix a lu et coûté,
 * et l'usage du mode (sessions, secondes parlées, réveils). Aucun contenu : ni texte ni audio.
 */
@Entity
@Table(name = "jarvis_usage_days")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisUsageDay {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire : toute lecture et toute écriture filtrent dessus. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "usage_day", nullable = false, updatable = false)
    private LocalDate day;

    @Builder.Default
    @Column(name = "chars_spoken", nullable = false)
    private long charsSpoken = 0L;

    @Builder.Default
    @Column(name = "speech_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal speechCostUsd = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "sessions", nullable = false)
    private int sessions = 0;

    @Builder.Default
    @Column(name = "spoken_seconds", nullable = false)
    private long spokenSeconds = 0L;

    @Builder.Default
    @Column(name = "wakes", nullable = false)
    private int wakes = 0;

    /** F-233 / SF-233-11 — retours au repos par fin de conversation mains libres (silence prolongé). */
    @Builder.Default
    @Column(name = "conversation_idle_ends", nullable = false)
    private int conversationIdleEnds = 0;

    /** F-233 / SF-233-04 — secondes de transcription en flux (écoute), comptées à la durée. */
    @Builder.Default
    @Column(name = "stt_stream_seconds", nullable = false)
    private long sttStreamSeconds = 0L;

    @Builder.Default
    @Column(name = "stt_stream_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal sttStreamCostUsd = BigDecimal.ZERO;

    /** F-235 / SF-235-01 (D15) — appels au cerveau rapide ce jour-là (pré-chauffages compris). */
    @Builder.Default
    @Column(name = "fast_brain_calls", nullable = false)
    private int fastBrainCalls = 0;

    /** F-235 / SF-235-01 (D15) — leur coût en dollars. Hors plafond de la voix : le cerveau rapide a le sien. */
    @Builder.Default
    @Column(name = "fast_brain_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal fastBrainCostUsd = BigDecimal.ZERO;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** F-233 / SF-233-04 — ce que la voix de Jarvis a coûté ce jour-là : lecture + écoute en flux, en dollars. */
    public BigDecimal totalCostUsd() {
        BigDecimal speech = speechCostUsd == null ? BigDecimal.ZERO : speechCostUsd;
        return sttStreamCostUsd == null ? speech : speech.add(sttStreamCostUsd);
    }
}
