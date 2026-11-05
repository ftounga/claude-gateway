package fr.claudegateway.atelier.live;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * <b>Un tour tourne-t-il sur ce poste, ou dans ce sujet — quel que soit le pod ?</b> (F-247 / SF-247-18, incident du
 * 2026-11-05).
 *
 * <p>Le registre des tours vivants est <b>en mémoire du pod</b> qui exécute. Sous HPA (2 replicas au moins), une
 * demande reçue par un autre pod ne voit pas le tour : c'est ainsi que la mise en place du relevé a ouvert un second
 * tour au milieu du tour de correctif du PO. Cette garde réunit donc, en plus du registre de ce pod :</p>
 * <ul>
 * <li><b>les résumés relayés des pairs</b> (F-191, au mieux : un pair muet ne dit rien) — sur demande seulement ;</li>
 * <li><b>une demande sans réponse</b> en base (message {@code USER} de moins de {@link #PENDING_WINDOW} sans
 * {@code ASSISTANT} postérieur sur le même fil) : écrite par le pod qui exécute au premier instant du tour, lue par
 * tous — c'est le signal qui fait foi entre pods, relais muet compris ;</li>
 * <li><b>une exécution de routine ou de chantier autonome</b> {@code RUNNING} (moins de {@link #RUN_WINDOW}).</li>
 * </ul>
 * <p>Pour un <b>poste</b>, s'y ajoute le journal du runner (commande hors relevés et veilles dans la fenêtre donnée).
 * Toute lecture est sous {@code user_id} (et {@code host_id}). En cas d'échec de lecture, la garde répond
 * « occupé » : un refus vaut mieux qu'une écriture au milieu d'un tour.</p>
 */
@Component
public class HostTurnActivity {

    private static final Logger log = LoggerFactory.getLogger(HostTurnActivity.class);
    /** Âge maximal d'une demande sans réponse tenue pour « tour en cours » (p99 prod sur 30 j : 24 min). */
    public static final Duration PENDING_WINDOW = Duration.ofMinutes(60);
    /** Âge maximal d'une exécution {@code RUNNING} tenue pour vivante (au-delà, le faucheur l'a déjà close). */
    public static final Duration RUN_WINDOW = Duration.ofHours(6);

    private static final String HOST_SUBJECTS = "SELECT w.id FROM workspaces w WHERE w.user_id = ? AND w.host_id = ?";
    private static final String UNANSWERED = "SELECT count(*) FROM atelier_messages u WHERE u.user_id = ? "
            + "AND u.role = 'USER' AND u.created_at >= ? AND u.workspace_id IN (%s) AND NOT EXISTS (SELECT 1 FROM "
            + "atelier_messages a WHERE a.workspace_id = u.workspace_id AND a.user_id = u.user_id AND a.role = "
            + "'ASSISTANT' AND a.created_at >= u.created_at AND ((a.chantier_number IS NULL AND u.chantier_number IS "
            + "NULL) OR a.chantier_number = u.chantier_number))";
    private static final String RUNNING = "SELECT (SELECT count(*) FROM routine_runs r WHERE r.user_id = ? AND "
            + "r.status = 'RUNNING' AND r.started_at >= ? AND r.workspace_id IN (%1$s)) + (SELECT count(*) FROM "
            + "chantier_autonomy_runs c WHERE c.user_id = ? AND c.status = 'RUNNING' AND c.started_at >= ? AND "
            + "c.workspace_id IN (%1$s))";
    private static final String COMMANDS = "SELECT count(*) FROM runner_audit a WHERE a.user_id = ? AND "
            + "a.workspace_id IN (" + HOST_SUBJECTS + ") AND a.created_at >= ? AND a.tool NOT LIKE 'veille_sonde%' "
            + "AND a.tool NOT LIKE 'releve_sonde%'";

    private final LiveTurnRegistry liveTurns;
    private final RemoteTurnSource remoteTurns;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public HostTurnActivity(LiveTurnRegistry liveTurns, RemoteTurnSource remoteTurns, JdbcTemplate jdbc, Clock clock) {
        this.liveTurns = liveTurns;
        this.remoteTurns = remoteTurns;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Vrai si un tour vit, ou attend, sur <b>n'importe quel sujet</b> du poste (terminal du poste, terminal Teams et
     * sujet dédié compris), ou si une commande runner hors relevés a eu lieu depuis {@code quiet}.
     *
     * @param askPeers interroger aussi les pods pairs (une requête HTTP par pair) — pour un geste rare et strict
     */
    public boolean hostBusy(UUID userId, UUID hostId, Duration quiet, boolean askPeers) {
        return hostBusy(userId, hostId, quiet, askPeers, OffsetDateTime.now(clock));
    }

    /** Même garde, à l'instant {@code now} donné par l'appelant (planificateur du relevé). */
    public boolean hostBusy(UUID userId, UUID hostId, Duration quiet, boolean askPeers, OffsetDateTime now) {
        if (userId == null || hostId == null || now == null) {
            return true;
        }
        try {
            Set<UUID> subjects = Set.copyOf(jdbc.queryForList(HOST_SUBJECTS, UUID.class, userId, hostId));
            if (subjects.isEmpty()) {
                return false;
            }
            if (liveHere(userId, subjects) || (askPeers && liveElsewhere(userId, subjects))) {
                return true;
            }
            if (count(UNANSWERED.formatted(HOST_SUBJECTS), userId, ts(now.minus(PENDING_WINDOW)), userId, hostId) > 0) {
                return true;
            }
            Timestamp runs = ts(now.minus(RUN_WINDOW));
            if (count(RUNNING.formatted(HOST_SUBJECTS), userId, runs, userId, hostId, userId, runs, userId, hostId) > 0) {
                return true;
            }
            return quiet != null && count(COMMANDS, userId, userId, hostId, ts(now.minus(quiet))) > 0;
        } catch (RuntimeException e) {
            log.warn("Activité du poste illisible : tenu pour occupé ({})", e.getClass().getSimpleName());
            return true;
        }
    }

    /**
     * Vrai si un tour vit, ou attend, dans ce <b>sujet</b> — sur n'importe lequel de ses fils, ici ou chez un pair —
     * ou si une routine / un chantier autonome y tourne. Pour un tour lancé sans humain (une routine) : jamais il ne
     * s'écrit au milieu du tour du PO.
     */
    public boolean subjectBusy(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return true;
        }
        try {
            Set<UUID> subjects = Set.of(workspaceId);
            if (liveHere(userId, subjects) || liveElsewhere(userId, subjects)) {
                return true;
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            String scope = "SELECT x.id FROM workspaces x WHERE x.user_id = ? AND x.id = ?";
            if (count(UNANSWERED.formatted(scope), userId, ts(now.minus(PENDING_WINDOW)), userId, workspaceId) > 0) {
                return true;
            }
            Timestamp runs = ts(now.minus(RUN_WINDOW));
            return count(RUNNING.formatted(scope), userId, runs, userId, workspaceId, userId, runs, userId,
                    workspaceId) > 0;
        } catch (RuntimeException e) {
            log.warn("Activité du sujet illisible : tenu pour occupé ({})", e.getClass().getSimpleName());
            return true;
        }
    }

    private boolean liveHere(UUID userId, Set<UUID> subjects) {
        return liveTurns.liveTurnsOf(userId).stream().anyMatch(t -> subjects.contains(t.workspaceId()));
    }

    private boolean liveElsewhere(UUID userId, Set<UUID> subjects) {
        List<TurnSummary> remote = remoteTurns.remoteSummariesOf(userId);
        return remote != null && remote.stream().anyMatch(s -> s != null && s.live() && userId.equals(s.userId())
                && subjects.contains(s.workspaceId()));
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static Timestamp ts(OffsetDateTime at) {
        return Timestamp.from(at.toInstant());
    }
}
