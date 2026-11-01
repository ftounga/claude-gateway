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

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
