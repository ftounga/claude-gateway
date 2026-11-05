package fr.claudegateway.shared.jobs;

import java.math.BigDecimal;
import java.sql.DatabaseMetaData;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>La file durable générique</b> (F-247 / SF-247-03, cadrage §4.0, D15) : une seule file en base, dans PostgreSQL,
 * pour tous les travaux asynchrones. Pas de Kafka ni de Redis.
 *
 * <ul>
 * <li><b>Prise</b> : {@code SELECT … FOR UPDATE SKIP LOCKED} puis passage en {@code RUNNING} avec un bail, dans la même
 * transaction ; sous H2 (dev, tests), {@code UPDATE} conditionnel ligne à ligne (même garantie : un seul preneur).</li>
 * <li><b>Faucheur</b> : un bail expiré (pod mort) remet le travail en attente, ou en {@code FAILED} au-delà des essais.</li>
 * <li><b>Recul</b> exponentiel entre essais ; <b>plafond</b> de travaux en attente par type (au-delà :
 * {@code DROPPED}, compté) ; <b>purge</b> des {@code DONE} après 7 j.</li>
 * <li>{@code payload_bytes} (l'image d'une capture) est mis à {@code null} dès la fin du travail, réussi ou non.</li>
 * </ul>
 *
 * <p>{@link #enqueue} s'inscrit dans la transaction de l'appelant s'il y en a une (un seul {@code INSERT}).</p>
 */
@Service
public class AsyncJobQueue {

    private static final Logger log = LoggerFactory.getLogger(AsyncJobQueue.class);

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String DROPPED = "DROPPED";
    static final List<String> STATUSES = List.of(PENDING, RUNNING, DONE, FAILED, DROPPED);

    static final Duration LEASE = Duration.ofMinutes(2);
    static final Duration BACKOFF_BASE = Duration.ofSeconds(15);
    static final Duration BACKOFF_MAX = Duration.ofHours(1);
    public static final int DEFAULT_PENDING_CAP = 2_000;
    static final int MAX_PAYLOAD_BYTES = 5 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean postgres;
    private final Map<String, Integer> pendingCaps = new java.util.concurrent.ConcurrentHashMap<>();

    public AsyncJobQueue(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock, DataSource dataSource) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.postgres = isPostgres(productName(dataSource));
    }

    /** Un travail pris. {@code payloadBytes} n'est lu que par son exécutant. */
    public record Job(UUID id, UUID userId, String kind, int priority, String payload, byte[] payloadBytes,
            int attempts, int maxAttempts, Instant createdAt) {
    }

    /** Ce qu'on met en file. */
    public record Request(UUID userId, String kind, int priority, String payload, byte[] payloadBytes,
            int maxAttempts) {

        public static Request of(UUID userId, String kind, String payload) {
            return new Request(userId, kind, 100, payload, null, 3);
        }
    }

    /** Le plafond de travaux en attente pour un type (par défaut {@value #DEFAULT_PENDING_CAP}). */
    public void setPendingCap(String kind, int cap) {
        pendingCaps.put(kind, Math.max(1, cap));
    }

    /**
     * Met un travail en file. Au-delà du plafond du type, il est rangé directement en {@code DROPPED} (compté, jamais
     * traité). Ne lève jamais : une file indisponible ne casse pas l'appelant.
     *
     * @return l'identifiant si le travail est en attente, vide s'il a été jeté ou n'a pu être inscrit
     */
    public Optional<UUID> enqueue(Request r) {
        if (r == null || r.userId() == null || r.kind() == null || r.kind().isBlank()) {
            return Optional.empty();
        }
        try {
            String kind = r.kind().strip().toUpperCase(Locale.ROOT);
            byte[] bytes = r.payloadBytes() != null && r.payloadBytes().length > MAX_PAYLOAD_BYTES ? null
                    : r.payloadBytes();
            Integer pending = jdbc.queryForObject("SELECT count(*) FROM async_jobs WHERE status = 'PENDING' AND kind = ?",
                    Integer.class, kind);
            boolean drop = pending != null && pending >= pendingCaps.getOrDefault(kind, DEFAULT_PENDING_CAP);
            UUID id = UUID.randomUUID();
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.update("INSERT INTO async_jobs (id, user_id, kind, priority, payload, payload_bytes, status, attempts, "
                    + "max_attempts, available_at, created_at, finished_at, error_code) VALUES (?,?,?,?,?,?,?,0,?,?,?,?,?)",
                    id, r.userId(), kind, r.priority(), r.payload(), drop ? null : bytes, drop ? DROPPED : PENDING,
                    Math.max(1, r.maxAttempts()), now, now, drop ? now : null, drop ? "pending_cap" : null);
            return drop ? Optional.empty() : Optional.of(id);
        } catch (RuntimeException e) {
            log.warn("File durable : mise en file impossible ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Prend jusqu'à {@code n} travaux en attente d'un type, au nom de {@code owner} (un seul preneur par travail). */
    public List<Job> claim(String kind, int n, String owner) {
        if (n <= 0) {
            return List.of();
        }
        String k = kind.strip().toUpperCase(Locale.ROOT);
        return postgres ? claimSkipLocked(k, n, owner) : claimConditional(k, n, owner);
    }

    private List<Job> claimSkipLocked(String kind, int n, String owner) {
        return tx.execute(s -> {
            Timestamp now = Timestamp.from(clock.instant());
            List<UUID> ids = jdbc.queryForList("SELECT id FROM async_jobs WHERE status = 'PENDING' AND kind = ? "
                    + "AND available_at <= ? ORDER BY priority, available_at LIMIT ? FOR UPDATE SKIP LOCKED",
                    UUID.class, kind, now, n);
            List<Job> out = new ArrayList<>();
            for (UUID id : ids) {
                jdbc.update("UPDATE async_jobs SET status = 'RUNNING', lease_owner = ?, lease_until = ?, "
                        + "attempts = attempts + 1 WHERE id = ?", owner, Timestamp.from(clock.instant().plus(LEASE)),
                        id);
                out.addAll(jdbc.query(SELECT_JOB + " WHERE id = ?", JOB, id));
            }
            return out;
        });
    }

    /** Repli H2 : {@code UPDATE} conditionnel, comme {@code claimSlot} des veilles. */
    private List<Job> claimConditional(String kind, int n, String owner) {
        Timestamp now = Timestamp.from(clock.instant());
        List<UUID> candidates = jdbc.queryForList("SELECT id FROM async_jobs WHERE status = 'PENDING' AND kind = ? "
                + "AND available_at <= ? ORDER BY priority, available_at LIMIT ?", UUID.class, kind, now, n * 2);
        List<Job> out = new ArrayList<>();
        for (UUID id : candidates) {
            if (out.size() >= n) {
                break;
            }
            int won = jdbc.update("UPDATE async_jobs SET status = 'RUNNING', lease_owner = ?, lease_until = ?, "
                    + "attempts = attempts + 1 WHERE id = ? AND status = 'PENDING'", owner,
                    Timestamp.from(clock.instant().plus(LEASE)), id);
            if (won == 1) {
                out.addAll(jdbc.query(SELECT_JOB + " WHERE id = ?", JOB, id));
            }
        }
        return out;
    }

    /** Fin réussie : {@code DONE}, image effacée. Sans effet si le bail n'est plus à {@code owner}. */
    public boolean complete(UUID id, String owner, long durationMs, BigDecimal costUsd) {
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.update("UPDATE async_jobs SET status = 'DONE', payload_bytes = NULL, lease_owner = NULL, "
                + "lease_until = NULL, finished_at = ?, duration_ms = ?, cost_usd = ?, error_code = NULL "
                + "WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?", now, durationMs, costUsd, id,
                owner) == 1;
    }

    /**
     * Échec : nouvel essai après recul exponentiel tant que {@code retryable} et que les essais le permettent, sinon
     * {@code FAILED} (image effacée).
     */
    public boolean fail(UUID id, String owner, String errorCode, boolean retryable, long durationMs,
            BigDecimal costUsd) {
        String code = errorCode == null ? "error" : errorCode.length() > 32 ? errorCode.substring(0, 32) : errorCode;
        List<int[]> state = jdbc.query("SELECT attempts, max_attempts FROM async_jobs WHERE id = ? AND status = "
                + "'RUNNING' AND lease_owner = ?", (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)}, id, owner);
        if (state.isEmpty()) {
            return false;
        }
        int attempts = state.get(0)[0];
        int max = state.get(0)[1];
        Timestamp now = Timestamp.from(clock.instant());
        if (retryable && attempts < max) {
            return jdbc.update("UPDATE async_jobs SET status = 'PENDING', lease_owner = NULL, lease_until = NULL, "
                    + "available_at = ?, error_code = ?, cost_usd = COALESCE(cost_usd, 0) + ? WHERE id = ? "
                    + "AND lease_owner = ?", Timestamp.from(clock.instant().plus(backoff(attempts))), code,
                    costUsd == null ? BigDecimal.ZERO : costUsd, id, owner) == 1;
        }
        return jdbc.update("UPDATE async_jobs SET status = 'FAILED', payload_bytes = NULL, lease_owner = NULL, "
                + "lease_until = NULL, finished_at = ?, duration_ms = ?, error_code = ?, "
                + "cost_usd = COALESCE(cost_usd, 0) + ? WHERE id = ? AND lease_owner = ?", now, durationMs, code,
                costUsd == null ? BigDecimal.ZERO : costUsd, id, owner) == 1;
    }

    /** Abandon volontaire (plafond de coût atteint, client coupé…) : {@code DROPPED}, image effacée. */
    public boolean drop(UUID id, String owner, String reason) {
        return jdbc.update("UPDATE async_jobs SET status = 'DROPPED', payload_bytes = NULL, lease_owner = NULL, "
                + "lease_until = NULL, finished_at = ?, error_code = ? WHERE id = ? AND lease_owner = ?",
                Timestamp.from(clock.instant()), reason == null ? "dropped" : reason, id, owner) == 1;
    }

    /** Le recul après {@code attempts} essais : 15 s, 30 s, 60 s… plafonné à 1 h. */
    static Duration backoff(int attempts) {
        long factor = 1L << Math.max(0, Math.min(attempts - 1, 20));
        Duration d = BACKOFF_BASE.multipliedBy(factor);
        return d.compareTo(BACKOFF_MAX) > 0 ? BACKOFF_MAX : d;
    }

    /** Le faucheur : baux expirés ⇒ en attente (ou {@code FAILED} au-delà des essais). */
    public int reap() {
        Timestamp now = Timestamp.from(clock.instant());
        int failed = jdbc.update("UPDATE async_jobs SET status = 'FAILED', payload_bytes = NULL, lease_owner = NULL, "
                + "lease_until = NULL, finished_at = ?, error_code = 'lease_expired' WHERE status = 'RUNNING' "
                + "AND lease_until < ? AND attempts >= max_attempts", now, now);
        int back = jdbc.update("UPDATE async_jobs SET status = 'PENDING', lease_owner = NULL, lease_until = NULL, "
                + "error_code = 'lease_expired' WHERE status = 'RUNNING' AND lease_until < ?", now);
        return failed + back;
    }

    /** Purge : {@code DONE} après 7 j, {@code FAILED}/{@code DROPPED} après 30 j. */
    public int purge() {
        Instant now = clock.instant();
        return jdbc.update("DELETE FROM async_jobs WHERE status = 'DONE' AND finished_at < ?",
                Timestamp.from(now.minus(Duration.ofDays(7))))
                + jdbc.update("DELETE FROM async_jobs WHERE status IN ('FAILED', 'DROPPED') AND finished_at < ?",
                        Timestamp.from(now.minus(Duration.ofDays(30))));
    }

    /** La mesure d'un compte, par type : nombres par statut, durée moyenne, coût cumulé (USD). */
    public Map<String, KindMeasure> measure(UUID userId, Instant since) {
        Map<String, KindMeasure> out = new LinkedHashMap<>();
        jdbc.query("SELECT kind, status, count(*), avg(duration_ms), sum(cost_usd) FROM async_jobs WHERE user_id = ? "
                + "AND created_at >= ? GROUP BY kind, status ORDER BY kind", rs -> {
                    String kind = rs.getString(1);
                    KindMeasure m = out.computeIfAbsent(kind, k -> new KindMeasure(new LinkedHashMap<>(), 0L,
                            BigDecimal.ZERO));
                    m.byStatus().put(rs.getString(2), rs.getLong(3));
                    long avg = rs.getObject(4) == null ? 0L : Math.round(rs.getDouble(4));
                    BigDecimal cost = rs.getBigDecimal(5) == null ? BigDecimal.ZERO : rs.getBigDecimal(5);
                    out.put(kind, new KindMeasure(m.byStatus(), Math.max(m.avgDurationMs(), avg), m.costUsd().add(cost)));
                }, userId, Timestamp.from(since));
        return out;
    }

    /** La mesure d'un type. */
    public record KindMeasure(Map<String, Long> byStatus, long avgDurationMs, BigDecimal costUsd) {
    }

    boolean postgres() {
        return postgres;
    }

    private static final String SELECT_JOB = "SELECT id, user_id, kind, priority, payload, payload_bytes, attempts, "
            + "max_attempts, created_at FROM async_jobs";

    private static final RowMapper<Job> JOB = (rs, i) -> new Job(rs.getObject(1, UUID.class),
            rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getBytes(6), rs.getInt(7),
            rs.getInt(8), rs.getTimestamp(9).toInstant());

    static boolean isPostgres(String product) {
        return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
    }

    private static String productName(DataSource dataSource) {
        if (dataSource == null) {
            return null;
        }
        try {
            return JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName);
        } catch (Exception e) {
            return null;
        }
    }
}
