package fr.claudegateway.atelier.veille;

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
 * <b>Un passage d'une veille</b> (F-230 / SF-230-02) : sans contenu — des instants, un statut, un nombre de
 * changements, un code d'erreur fermé. Nourrit l'historique et la mesure d'adoption (D8).
 */
@Entity
@Table(name = "jarvis_watch_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisWatchRun {

    /** Premier passage : l'instantané de base est posé, rien n'est signalé. */
    public static final String BASELINE = "BASELINE";
    /** Rien n'a bougé : coût ≈ 0. */
    public static final String NO_CHANGE = "NO_CHANGE";
    /** Quelque chose a bougé : SF-230-03 interpelle. */
    public static final String CHANGED = "CHANGED";
    /** La sonde a échoué. */
    public static final String FAILED = "FAILED";
    /** Poste hors ligne au créneau : rattrapé une fois à la reconnexion. */
    public static final String MISSED_OFFLINE = "MISSED_OFFLINE";

    public static final String SCHEDULED = "SCHEDULED";
    public static final String CATCH_UP = "CATCH_UP";
    public static final String MANUAL = "MANUAL";

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

    @Column(name = "slot_at")
    private OffsetDateTime slotAt;

    @Column(name = "trigger_kind", nullable = false, length = 16)
    private String trigger;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Builder.Default
    @Column(name = "change_count", nullable = false)
    private int changeCount = 0;

    @Column(name = "error_code", length = 40)
    private String errorCode;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;
}
