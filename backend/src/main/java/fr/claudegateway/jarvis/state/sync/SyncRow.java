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

    /** SF-247-17 — la phase des constats sur l'historique (numérotée 4, jouée juste après l'historique). */
    public static final int PHASE_NOTES = 4;

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

    /** SF-247-17 — phase 4 : les constats sur l'historique (après l'historique, sans le poste). */
    private String p4Status = P_PENDING;
    /** Sorties à lire (estimées pendant l'historique). */
    private int p4Total;
    /** Sorties lues. */
    private int p4Done;
    /** Faits écrits au registre (créés ou modifiés). */
    private int p4Facts;
    /** Faits retenus par la garde de citation. */
    private int p4Kept;
    /** Faits rejetés par la garde de citation. */
    private int p4Rejected;
    private int p4Calls;
    private int p4Errors;
    private long p4EstChars;
    private int p4EstCalls;
    private long p4Ms;
    private BigDecimal p4CostUsd = BigDecimal.ZERO;
    /** L'enveloppe ponctuelle du passage, en euros (distincte du plafond mensuel des constats). */
    private BigDecimal p4EnvelopeEur = BigDecimal.ZERO;
    private String p4Detail;

    private OffsetDateTime startedAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime finishedAt;

    /** Le passage travaille encore (ni fini, ni annulé). */
    public boolean active() {
        return RUNNING.equals(status) && finishedAt == null;
    }
}
