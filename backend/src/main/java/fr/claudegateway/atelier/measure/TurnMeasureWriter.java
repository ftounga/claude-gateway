package fr.claudegateway.atelier.measure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Écrit et purge les mesures de tour (F-188 / SF-188-03). <b>N'échoue jamais vers l'appelant</b> :
 * une mesure perdue ne doit pas coûter un tour.
 */
@Component
public class TurnMeasureWriter {

    private static final Logger log = LoggerFactory.getLogger(TurnMeasureWriter.class);

    /** Au-delà, une mesure n'éclaire plus une décision : elle est purgée. */
    public static final long RETENTION_DAYS = 180L;

    private static final int MAX_MODEL_CHARS = 64;
    private static final int MAX_STOP_CHARS = 64;

    private final JdbcTemplate jdbcTemplate;

    public TurnMeasureWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Range la mesure ; journalise et oublie toute panne. */
    public void write(TurnMeasure measure) {
        try {
            jdbcTemplate.update("INSERT INTO atelier_turn_measures (id, user_id, workspace_id, "
                            + "host_id, model, occurred_at, duration_ms, iterations, stop_cause, "
                            + "max_context_tokens, total_context_tokens, first_pass_cache_write, "
                            + "later_cache_write, input_tokens, output_tokens, cache_read_tokens, "
                            + "cache_write_tokens, web_search_requests, runner_refusals, "
                            + "runner_busy_waits) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                            + "?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), measure.userId(), measure.workspaceId(), measure.hostId(),
                    bounded(measure.model(), MAX_MODEL_CHARS),
                    Timestamp.from(measure.occurredAt()), measure.durationMs(), measure.iterations(),
                    bounded(measure.stopCause() == null ? "inconnu" : measure.stopCause(),
                            MAX_STOP_CHARS),
                    measure.maxContextTokens(), measure.totalContextTokens(),
                    measure.firstPassCacheWrite(), measure.laterCacheWrite(),
                    measure.inputTokens(), measure.outputTokens(), measure.cacheReadTokens(),
                    measure.cacheWriteTokens(), measure.webSearchRequests(),
                    measure.runnerRefusals(), measure.runnerBusyWaits());
        } catch (RuntimeException e) {
            log.warn("Mesure du tour non enregistrée (workspace={}) : {}", measure.workspaceId(),
                    e.getMessage());
        }
    }

    /** Purge à la suppression du compte. */
    public int deleteByUserId(UUID userId) {
        return jdbcTemplate.update("DELETE FROM atelier_turn_measures WHERE user_id = ?", userId);
    }

    /** Purge des mesures plus anciennes que {@code before}. */
    public int purgeOlderThan(Instant before) {
        return jdbcTemplate.update("DELETE FROM atelier_turn_measures WHERE occurred_at < ?",
                Timestamp.from(before));
    }

    private static String bounded(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
