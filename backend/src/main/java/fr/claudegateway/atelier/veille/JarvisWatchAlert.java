package fr.claudegateway.atelier.veille;

import java.math.BigDecimal;
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
 * <b>Une alerte de veille</b> (F-230 / SF-230-03, D4) : ce qui a bougé, dit en une phrase, et ce que cela a coûté.
 * Lue par Jarvis (F-231 / SF-231-05) ; {@code openedAt} nourrit la mesure d'adoption (D8).
 */
@Entity
@Table(name = "jarvis_watch_alerts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisWatchAlert {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "watch_id", nullable = false, updatable = false)
    private UUID watchId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "chantier_number")
    private Integer chantierNumber;

    @Column(name = "sentence", nullable = false, length = 500)
    private String sentence;

    @Column(name = "changes_json", nullable = false, columnDefinition = "text")
    private String changesJson;

    @Column(name = "change_count", nullable = false)
    private int changeCount;

    /** Vrai si la phrase vient de l'IA ; faux pour la phrase déterministe (budget, quota, fournisseur). */
    @Builder.Default
    @Column(name = "ai_written", nullable = false)
    private boolean aiWritten = false;

    @Builder.Default
    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal costUsd = BigDecimal.ZERO;

    @Column(name = "opened_at")
    private OffsetDateTime openedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
