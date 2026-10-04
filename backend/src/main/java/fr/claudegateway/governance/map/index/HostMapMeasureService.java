package fr.claudegateway.governance.map.index;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>La mesure d'avant et d'après</b> (F-174 / SF-174-01 et SF-174-07).
 *
 * <p>Deux fenêtres de même durée de part et d'autre d'une date pivot (la mise en service de l'index),
 * et pour chacune : combien de fois l'agent a <b>fouillé</b> la carte lui-même ({@code bash},
 * {@code grep}, {@code read_file} sur un fichier de carte — lu dans {@code runner_audit}), combien de
 * faits la gateway lui a <b>joints</b>, avec quelle stratégie, combien de pièges et d'échéances, et
 * combien d'appels à {@code carte_chercher}. Un verdict sobre dit si le coupe-circuit doit être
 * envisagé.</p>
 *
 * <p><b>Isolation</b> : tout est filtré par {@code user_id} — l'administrateur voit SES postes.</p>
 */
@Service
public class HostMapMeasureService {

    /** Les fouilles de la carte par l'agent : un appel runner dont la cible cite un fichier de carte du même poste. */
    static final String DIGS_SQL = "SELECT count(*) FROM runner_audit a "
            + "WHERE a.user_id = ? AND a.created_at >= ? AND a.created_at < ? "
            + "AND a.tool IN ('bash', 'grep', 'read_file') "
            + "AND EXISTS (SELECT 1 FROM host_map_files f WHERE f.user_id = a.user_id "
            + "AND f.host_id = a.host_id AND a.target LIKE CONCAT('%', f.path, '%'))";

    static final String LOOKUPS_SQL = "SELECT kind, strategy, count(*) AS n, coalesce(sum(facts_count), 0) AS facts, "
            + "coalesce(sum(chars), 0) AS chars, coalesce(sum(pitfalls_count), 0) AS pitfalls, "
            + "coalesce(sum(deadlines_count), 0) AS deadlines FROM host_map_lookups "
            + "WHERE user_id = ? AND created_at >= ? AND created_at < ? GROUP BY kind, strategy";

    /** Ce qu'une fenêtre a vu. */
    public record Window(OffsetDateTime from, OffsetDateTime to, long turns, long hybridTurns,
            long emptyTurns, long factsJoined, long charsJoined, long pitfalls, long deadlines,
            long toolCalls, long mapDigs) {

        /** Fouilles de la carte par tour d'un poste à carte. */
        public double digsPerTurn() {
            return turns == 0 ? 0 : (double) mapDigs / turns;
        }

        /** Faits joints par tour. */
        public double factsPerTurn() {
            return turns == 0 ? 0 : (double) factsJoined / turns;
        }
    }

    /** La comparaison, et ce qu'elle suggère. */
    public record Comparison(Window before, Window after, String verdict, List<String> notes) {
    }

    private final JdbcTemplate jdbc;

    public HostMapMeasureService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Une fenêtre. */
    @Transactional(readOnly = true)
    public Window window(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        Timestamp start = Timestamp.from(from.toInstant());
        Timestamp end = Timestamp.from(to.toInstant());
        Long digs = jdbc.queryForObject(DIGS_SQL, Long.class, userId, start, end);
        long[] acc = new long[8]; // turns, hybrid, empty, facts, chars, pitfalls, deadlines, tool
        jdbc.query(LOOKUPS_SQL, rs -> {
            String kind = rs.getString("kind");
            String strategy = rs.getString("strategy");
            long n = rs.getLong("n");
            if (HostMapLookup.KIND_TOOL.equals(kind)) {
                acc[7] += n;
                return;
            }
            acc[0] += n;
            if (HostMapLookup.STRATEGY_HYBRID.equals(strategy)) {
                acc[1] += n;
            }
            if (HostMapLookup.STRATEGY_NONE.equals(strategy)) {
                acc[2] += n;
            }
            acc[3] += rs.getLong("facts");
            acc[4] += rs.getLong("chars");
            acc[5] += rs.getLong("pitfalls");
            acc[6] += rs.getLong("deadlines");
        }, userId, start, end);
        return new Window(from, to, acc[0], acc[1], acc[2], acc[3], acc[4], acc[5], acc[6], acc[7],
                digs == null ? 0 : digs);
    }

    /**
     * Avant / après autour du pivot, sur {@code days} jours chacun (l'après s'arrête à {@code now}).
     *
     * <p><b>Seuils de retour arrière (SF-174-07)</b> : si, après, les fouilles de la carte par tour
     * ne baissent pas, ou si plus d'un tour sur deux ne reçoit rien alors que l'index est en service,
     * le verdict propose d'éteindre l'index ({@code APP_MAP_INDEX_ENABLED=false}) le temps de
     * comprendre. Le jugement de justesse (relances du PO) reste humain : la mesure l'éclaire, elle ne
     * le remplace pas.</p>
     */
    public Comparison compare(UUID userId, OffsetDateTime pivot, int days, OffsetDateTime now) {
        OffsetDateTime afterEnd = pivot.plusDays(days).isAfter(now) ? now : pivot.plusDays(days);
        Window before = window(userId, pivot.minusDays(days), pivot);
        Window after = window(userId, pivot, afterEnd.isBefore(pivot) ? pivot : afterEnd);
        List<String> notes = new java.util.ArrayList<>();
        String verdict;
        if (before.turns() == 0 || after.turns() == 0) {
            verdict = "INSUFFISANT";
            notes.add("Pas assez de tours sur une des deux fenêtres pour comparer.");
        } else {
            boolean digsDown = after.digsPerTurn() < before.digsPerTurn();
            boolean mostlyEmpty = after.emptyTurns() * 2 > after.turns();
            if (!digsDown || mostlyEmpty) {
                verdict = "RETOUR_ARRIERE_A_ENVISAGER";
                if (!digsDown) {
                    notes.add("Les fouilles de la carte par tour ne baissent pas ("
                            + String.format(java.util.Locale.ROOT, "%.2f → %.2f", before.digsPerTurn(),
                                    after.digsPerTurn()) + ").");
                }
                if (mostlyEmpty) {
                    notes.add("Plus d'un tour sur deux ne reçoit aucun fait de la carte.");
                }
                notes.add("Coupe-circuit : APP_MAP_INDEX_ENABLED=false (retour au rappel lexical F-137).");
            } else {
                verdict = "GAIN";
                notes.add("Fouilles de la carte par tour : " + String.format(java.util.Locale.ROOT,
                        "%.2f → %.2f", before.digsPerTurn(), after.digsPerTurn()) + ".");
            }
        }
        notes.add("À compléter par la lecture humaine : relances du PO sur des faits que la carte portait.");
        return new Comparison(before, after, verdict, notes);
    }
}
