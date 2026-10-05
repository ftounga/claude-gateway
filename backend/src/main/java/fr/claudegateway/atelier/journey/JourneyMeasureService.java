package fr.claudegateway.atelier.journey;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>La mesure du parcours du sujet</b> (F-176 / SF-176-06) : ce que le mode guidé a changé, avant /
 * après un pivot, et guidé / libre sur la même fenêtre.
 *
 * <p>Trois sources, toutes sous {@code user_id} :</p>
 * <ul>
 *   <li>le <b>journal du parcours</b> ({@code subject_journey_events}) : propositions, acceptations,
 *       plans validés, amendements, refus de la porte (par classe), retours en investigation,
 *       vérifications en échec, clôtures ;</li>
 *   <li>le <b>journal d'exécution</b> ({@code runner_audit}) : appels d'outil, échecs, et <b>retours
 *       arrière</b> (commandes {@code git revert}, {@code git reset --hard}, {@code rollback},
 *       {@code rollout undo}) — par terminal guidé ou libre ;</li>
 *   <li>la <b>lecture humaine</b>, pour ce qu'aucune table ne porte : les incidents (audit F-172) et
 *       les relances du PO — dite dans les notes, jamais inventée.</li>
 * </ul>
 */
@Service
public class JourneyMeasureService {

    static final String EVENTS_SQL = "SELECT type, count(*) AS n FROM subject_journey_events "
            + "WHERE user_id = ? AND created_at >= ? AND created_at < ? GROUP BY type";

    static final String GATE_SQL = "SELECT detail FROM subject_journey_events "
            + "WHERE user_id = ? AND created_at >= ? AND created_at < ? AND type = 'GATE_BLOCKED'";

    static final String GUIDED_SQL = "SELECT DISTINCT workspace_id FROM subject_journey_events "
            + "WHERE user_id = ? AND created_at >= ? AND created_at < ? AND mode = 'GUIDE'";

    static final String STEP_FAILED_SQL = "SELECT count(*) FROM subject_journey_events "
            + "WHERE user_id = ? AND created_at >= ? AND created_at < ? AND type = 'STEP_UPDATED' "
            + "AND detail LIKE '% · ECHEC'";

    static final String CALLS_SQL = "SELECT workspace_id, count(*) AS calls, "
            + "sum(CASE WHEN outcome <> 'OK' THEN 1 ELSE 0 END) AS failures, "
            + "sum(CASE WHEN tool = 'bash' AND (lower(target) LIKE '%git revert%' "
            + "OR lower(target) LIKE '%git reset --hard%' OR lower(target) LIKE '%rollback%' "
            + "OR lower(target) LIKE '%rollout undo%') THEN 1 ELSE 0 END) AS rollbacks "
            + "FROM runner_audit WHERE user_id = ? AND created_at >= ? AND created_at < ? "
            + "AND workspace_id IS NOT NULL GROUP BY workspace_id";

    private final JdbcTemplate jdbc;

    public JourneyMeasureService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Une fenêtre.
     *
     * @param events          nombre de chaque geste du parcours
     * @param gateBlocked     refus de la porte par classe de risque
     * @param guidedSubjects  terminaux passés par le mode guidé sur la fenêtre
     * @param stepsFailed     vérifications en échec
     * @param guided          l'exécution des terminaux guidés
     * @param libre           l'exécution des autres terminaux
     */
    public record Window(OffsetDateTime from, OffsetDateTime to, Map<String, Long> events,
                         Map<String, Long> gateBlocked, long guidedSubjects, long stepsFailed,
                         Execution guided, Execution libre) {
    }

    /** L'exécution d'un groupe de terminaux : appels, échecs, retours arrière. */
    public record Execution(long terminals, long calls, long failures, long rollbacks) {

        /** Retours arrière par terminal actif. */
        public double rollbacksPerTerminal() {
            return terminals == 0 ? 0 : (double) rollbacks / terminals;
        }

        /** Échecs d'outil pour 100 appels. */
        public double failuresPer100Calls() {
            return calls == 0 ? 0 : 100.0 * failures / calls;
        }
    }

    /** La comparaison : avant et après le pivot, et ce qu'il faut lire en plus. */
    public record Comparison(Window before, Window after, List<String> notes) {
    }

    /** Une fenêtre {@code [from, to)} pour ce compte. */
    @Transactional(readOnly = true)
    public Window window(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        Timestamp start = Timestamp.from(from.toInstant());
        Timestamp end = Timestamp.from(to.toInstant());
        Map<String, Long> events = new TreeMap<>();
        jdbc.query(EVENTS_SQL, rs -> {
            events.put(rs.getString("type"), rs.getLong("n"));
        }, userId, start, end);
        Map<String, Long> gate = new TreeMap<>();
        jdbc.query(GATE_SQL, rs -> {
            String detail = rs.getString("detail");
            String risk = detail == null ? "?" : detail.split(" · ", 2)[0];
            gate.merge(risk, 1L, Long::sum);
        }, userId, start, end);
        Set<String> guidedIds = new HashSet<>();
        jdbc.query(GUIDED_SQL, rs -> {
            guidedIds.add(rs.getString("workspace_id"));
        }, userId, start, end);
        Long failedSteps = jdbc.queryForObject(STEP_FAILED_SQL, Long.class, userId, start, end);
        long[] g = new long[4];
        long[] l = new long[4];
        Map<String, Boolean> seen = new HashMap<>();
        jdbc.query(CALLS_SQL, rs -> {
            String workspace = rs.getString("workspace_id");
            long[] acc = guidedIds.contains(workspace) ? g : l;
            if (seen.putIfAbsent(workspace, Boolean.TRUE) == null) {
                acc[0]++;
            }
            acc[1] += rs.getLong("calls");
            acc[2] += rs.getLong("failures");
            acc[3] += rs.getLong("rollbacks");
        }, userId, start, end);
        return new Window(from, to, events, gate, guidedIds.size(), failedSteps == null ? 0 : failedSteps,
                new Execution(g[0], g[1], g[2], g[3]), new Execution(l[0], l[1], l[2], l[3]));
    }

    /**
     * Avant / après {@code pivot}, sur {@code days} jours de chaque côté (l'après s'arrête à
     * {@code now}).
     */
    @Transactional(readOnly = true)
    public Comparison compare(UUID userId, OffsetDateTime pivot, int days, OffsetDateTime now) {
        OffsetDateTime afterEnd = pivot.plusDays(days).isAfter(now) ? now : pivot.plusDays(days);
        Window before = window(userId, pivot.minusDays(days), pivot);
        Window after = window(userId, pivot, afterEnd.isBefore(pivot) ? pivot : afterEnd);
        List<String> notes = new ArrayList<>();
        if (after.guidedSubjects() == 0) {
            notes.add("Aucun sujet guidé sur la fenêtre d'après : rien à comparer encore.");
        } else {
            notes.add(String.format(java.util.Locale.ROOT,
                    "Retours arrière par terminal : guidé %.2f · libre %.2f (avant : %.2f).",
                    after.guided().rollbacksPerTerminal(), after.libre().rollbacksPerTerminal(),
                    before.libre().rollbacksPerTerminal() + before.guided().rollbacksPerTerminal()));
            notes.add(String.format(java.util.Locale.ROOT,
                    "Échecs d'outil pour 100 appels : guidé %.1f · libre %.1f.",
                    after.guided().failuresPer100Calls(), after.libre().failuresPer100Calls()));
            long blocked = after.gateBlocked().values().stream().mapToLong(Long::longValue).sum();
            notes.add("Modifications retenues par la porte avant validation : " + blocked + ".");
        }
        notes.add("À compléter par la lecture humaine : incidents (grille F-172 — vérification, compréhension, "
                + "raisonnement) et relances du PO, qu'aucune table ne porte.");
        return new Comparison(before, after, notes);
    }
}
