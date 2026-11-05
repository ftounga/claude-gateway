package fr.claudegateway.jarvis.state.sync;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <b>Un passage de synchronisation</b> (F-247 / SF-247-16), tel que rangé dans {@code jarvis_state_syncs} : statut
 * général, phase courante (1 historique, 2 captures, 3 relevé du jour), étape de la chaîne de tranches, situation
 * ({@code note} : tour en cours, poste éteint, lectures en file), curseur, et la mesure de chaque phase.
 */
@Data
@NoArgsConstructor
public class SyncRow {

    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String CANCELLED = "CANCELLED";

    /** États d'une phase. */
    public static final String P_PENDING = "PENDING";
    public static final String P_RUNNING = "RUNNING";
    public static final String P_WAITING = "WAITING";
    public static final String P_PAUSED = "PAUSED";
    public static final String P_DONE = "DONE";
    public static final String P_SKIPPED = "SKIPPED";
    public static final String P_EXPIRED = "EXPIRED";

    /** Situations dites à l'écran. */
    public static final String NOTE_TURN = "TURN";
    public static final String NOTE_HOST_OFF = "HOST_OFF";
    public static final String NOTE_READING = "READING";

    private UUID id;
    private UUID userId;
    private UUID hostId;
    private String status;
    private int phase;
    private int step;
    private String note;
    private int windowDays;
    private OffsetDateTime cursorAt;
    private UUID cursorId;
    private OffsetDateTime waitingSince;

    private String p1Status;
    private int p1Total;
    private int p1Done;
    private int p1Facts;
    private long p1Ms;
    private String p1Detail;

    private String p2Status;
    private int p2Total;
    private int p2Done;
    private int p2Facts;
    private int p2Read;
    private int p2Missing;
    private int p2Dropped;
    private long p2Ms;
    private BigDecimal p2CostUsd = BigDecimal.ZERO;
    private OffsetDateTime p2StartedAt;
    private String p2Detail;

    private String p3Status;
    private int p3Total;
    private int p3Done;
    private int p3Facts;
    private long p3Ms;
    private OffsetDateTime p3StartedAt;
    private String p3Detail;

    private OffsetDateTime startedAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime finishedAt;

    /** Le passage travaille encore (ni fini, ni annulé). */
    public boolean active() {
        return RUNNING.equals(status) && finishedAt == null;
    }
}
