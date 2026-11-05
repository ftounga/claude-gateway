package fr.claudegateway.jarvis.state;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import fr.claudegateway.jarvis.JarvisSpokenWords;
import fr.claudegateway.jarvis.timing.JarvisLatencyService;
import fr.claudegateway.jarvis.timing.JarvisTurnTiming;
import fr.claudegateway.jarvis.timing.JarvisTurnTimingRepository;

/**
 * <b>La mesure du registre d'état</b> (F-247 / SF-247-01, cadrage §6-§7) — les bases des portes, prises <b>avant</b>
 * tout le reste : part des « je ne sais pas » poste éteint (indicateur principal), premier mot de Jarvis à la voix
 * (Z3/Z13), durée des tours Opus (Z2) et charge runner. Agrégats seulement, sur les données du compte appelant.
 */
@Service
public class JarvisStateMeasureService {

    static final int MAX_DAYS = 90;

    private final JarvisTurnTimingRepository timings;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JarvisStateMeasureService(JarvisTurnTimingRepository timings, JdbcTemplate jdbc, Clock clock) {
        this.timings = timings;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Measure measure(UUID userId, int days) {
        int window = Math.max(1, Math.min(days, MAX_DAYS));
        OffsetDateTime since = OffsetDateTime.now(clock).minusDays(window);
        List<JarvisTurnTiming> rows = timings.findByUserIdAndCreatedAtGreaterThanEqual(userId, since);
        List<JarvisTurnTiming> questions = rows.stream().filter(r -> Boolean.TRUE.equals(r.getStateQuestion()))
                .toList();
        List<JarvisTurnTiming> offline = questions.stream().filter(r -> Boolean.TRUE.equals(r.getHostOffline()))
                .toList();
        Map<String, Long> answers = new LinkedHashMap<>();
        JarvisLatencyService.STATE_ANSWERS.forEach(a -> answers.put(a,
                offline.stream().filter(r -> a.equals(r.getStateAnswer())).count()));
        long unknown = answers.get("UNKNOWN");
        Double unknownRate = offline.isEmpty() ? null : Math.round(unknown * 1000.0 / offline.size()) / 1000.0;

        Latency all = latency(rows, r -> true);
        Latency onState = latency(rows, r -> Boolean.TRUE.equals(r.getStateQuestion()));
        return new Measure(window, rows.size(), questions.size(), offline.size(), answers, unknownRate, all, onState,
                p(rows, JarvisTurnTiming::getStateBlockMs, 95), opusTurns(userId, since), runnerLoad(userId, since),
                coverage(userId, since), jobs == null ? Map.of() : jobs.measure(userId, since.toInstant()),
                captures(userId, since), conversationReads(userId, since));
    }

    /** SF-247-12/13 — les captures du compte par issue (lues, pas une conversation, illisibles) et leurs lignes. */
    Captures captures(UUID userId, OffsetDateTime since) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbc.query("SELECT status, count(*) FROM jarvis_conversation_captures WHERE user_id = ? AND created_at >= ? "
                + "GROUP BY status ORDER BY status", rs -> {
                    byStatus.put(rs.getString(1), rs.getLong(2));
                }, userId, Timestamp.from(since.toInstant()));
        Long lines = jdbc.queryForObject("SELECT count(*) FROM jarvis_capture_lines WHERE user_id = ? AND created_at >= ?",
                Long.class, userId, Timestamp.from(since.toInstant()));
        return new Captures(byStatus, lines == null ? 0 : lines);
    }

    /** SF-247-13 — les « qu'a dit X » : lectures de l'outil {@code conversations} sur la période. */
    long conversationReads(UUID userId, OffsetDateTime since) {
        Long n = jdbc.queryForObject("SELECT coalesce(sum(conversation_reads), 0) FROM jarvis_usage_days WHERE "
                + "user_id = ? AND usage_day >= ?", Long.class, userId, java.sql.Date.valueOf(since.toLocalDate()));
        return n == null ? 0 : n;
    }

    /** Les captures lues. */
    public record Captures(Map<String, Long> byStatus, long lines) {
    }

    static Latency latency(List<JarvisTurnTiming> rows, Predicate<JarvisTurnTiming> keep) {
        List<JarvisTurnTiming> kept = rows.stream().filter(keep).toList();
        return new Latency(kept.size(), p(kept, JarvisTurnTiming::getFirstSoundMs, 50),
                p(kept, JarvisTurnTiming::getFirstSoundMs, 90), p(kept, JarvisTurnTiming::getFastFirstTokenMs, 50),
                p(kept, JarvisTurnTiming::getFastFirstTokenMs, 90));
    }

    private static Integer p(List<JarvisTurnTiming> rows, Function<JarvisTurnTiming, Integer> read, int pct) {
        List<Integer> v = rows.stream().map(read).filter(x -> x != null).sorted().toList();
        return JarvisSpokenWords.percentile(v, pct);
    }

    /** Z2 — durée des tours Opus du compte (p50/p90), lue sous {@code user_id}. */
    Durations opusTurns(UUID userId, OffsetDateTime since) {
        List<Long> v = jdbc.queryForList("SELECT duration_ms FROM atelier_turn_measures WHERE user_id = ? "
                + "AND occurred_at >= ? ORDER BY duration_ms", Long.class, userId, Timestamp.from(since.toInstant()));
        return durations(v);
    }

    /** La charge runner : appels d'outils du compte (nombre, p50/p90 de durée), lus sous {@code user_id}. */
    Durations runnerLoad(UUID userId, OffsetDateTime since) {
        List<Long> v = jdbc.queryForList("SELECT duration_ms FROM runner_audit WHERE user_id = ? AND created_at >= ? "
                + "AND duration_ms IS NOT NULL ORDER BY duration_ms", Long.class, userId,
                Timestamp.from(since.toInstant()));
        return durations(v);
    }

    static Durations durations(List<Long> sorted) {
        List<Long> v = sorted.stream().filter(x -> x != null).sorted().toList();
        return new Durations(v.size(), at(v, 50), at(v, 90));
    }

    private static Long at(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) {
            return null;
        }
        int rank = (int) Math.ceil(pct / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    /**
     * SF-247-04 — le taux de couverture des extracteurs : appels {@code bash} d'état vus, couverts (au moins un fait),
     * faits, et les 20 formes les plus fréquentes restées sans fait (la liste de travail de l'extracteur suivant).
     */
    Coverage coverage(UUID userId, OffsetDateTime since) {
        java.sql.Date from = java.sql.Date.valueOf(since.toLocalDate());
        List<Form> forms = jdbc.query("SELECT form, sum(calls), sum(covered), sum(facts) FROM jarvis_state_coverage "
                + "WHERE user_id = ? AND obs_day >= ? GROUP BY form ORDER BY sum(calls) DESC", (rs, i) -> new Form(
                        rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)), userId, from);
        long calls = forms.stream().mapToLong(Form::calls).sum();
        long covered = forms.stream().mapToLong(Form::covered).sum();
        long facts = forms.stream().mapToLong(Form::facts).sum();
        List<Form> uncovered = forms.stream().filter(f -> f.covered() < f.calls()).limit(20).toList();
        return new Coverage(calls, covered, facts, calls == 0 ? null : Math.round(covered * 1000.0 / calls) / 1000.0,
                uncovered);
    }

    /** Une forme de commande d'état et ses compteurs. */
    public record Form(String form, long calls, long covered, long facts) {
    }

    /** Le taux de couverture. */
    public record Coverage(long stateCalls, long covered, long facts, Double rate, List<Form> uncoveredForms) {
    }

    /** SF-247-04 — la file durable, pour la mesure par type (facultative). */
    private fr.claudegateway.shared.jobs.AsyncJobQueue jobs;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJobs(fr.claudegateway.shared.jobs.AsyncJobQueue jobs) {
        this.jobs = jobs;
    }

    /** Premier son dit et premier jeton du cerveau rapide, p50/p90 en ms. */
    public record Latency(int turns, Integer firstSoundP50, Integer firstSoundP90, Integer fastFirstTokenP50,
            Integer fastFirstTokenP90) {
    }

    /** Un nombre et ses p50/p90 en ms. */
    public record Durations(int count, Long p50, Long p90) {
    }

    /**
     * La mesure rendue à l'administrateur.
     *
     * @param offlineAnswers réponses aux questions d'état posées poste éteint, par issue
     * @param unknownRate    part des {@code UNKNOWN} parmi elles (l'indicateur principal, cible −50 % à J+14)
     */
    public record Measure(int days, int turns, int stateQuestions, int offlineStateQuestions,
            Map<String, Long> offlineAnswers, Double unknownRate, Latency voice, Latency voiceOnStateQuestions,
            Integer stateBlockMsP95, Durations opusTurns, Durations runnerCalls, Coverage coverage,
            Map<String, fr.claudegateway.shared.jobs.AsyncJobQueue.KindMeasure> jobs, Captures captures,
            long conversationQuestions) {
    }
}
