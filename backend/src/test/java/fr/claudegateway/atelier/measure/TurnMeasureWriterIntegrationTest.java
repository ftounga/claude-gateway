package fr.claudegateway.atelier.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** La mesure par tour en base (F-188 / SF-188-03) : écriture, purge d'un compte, purge par âge. */
@SpringBootTest
@ActiveProfiles("test")
class TurnMeasureWriterIntegrationTest {

    @Autowired private TurnMeasureWriter writer;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static TurnMeasure measure(UUID userId, Instant at) {
        return new TurnMeasure(userId, UUID.randomUUID(), null, "claude-opus-5-5", at, 42_000L, 3,
                "réponse finale", 150_000L, 400_000L, 12_000L, 3_000L, 450_000L, 2_000L, 380_000L,
                15_000L, 1, 2, 1);
    }

    @Test
    void aTurnIsWrittenWithItsNumbersOnly() {
        UUID userId = UUID.randomUUID();
        writer.write(measure(userId, Instant.now()));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM atelier_turn_measures WHERE user_id = ?", userId);
        assertThat(row.get("iterations")).isEqualTo(3);
        assertThat(row.get("stop_cause")).isEqualTo("réponse finale");
        assertThat(((Number) row.get("max_context_tokens")).longValue()).isEqualTo(150_000L);
        assertThat(((Number) row.get("first_pass_cache_write")).longValue()).isEqualTo(12_000L);
        assertThat(((Number) row.get("later_cache_write")).longValue()).isEqualTo(3_000L);
        assertThat(row.get("runner_refusals")).isEqualTo(2);
        assertThat(row.get("runner_busy_waits")).isEqualTo(1);
    }

    @Test
    void deletingAnAccountPurgesOnlyItsMeasures() {
        UUID leaving = UUID.randomUUID();
        UUID staying = UUID.randomUUID();
        writer.write(measure(leaving, Instant.now()));
        writer.write(measure(staying, Instant.now()));

        writer.deleteByUserId(leaving);

        assertThat(count(leaving)).isZero();
        assertThat(count(staying)).isEqualTo(1);
    }

    @Test
    void measuresOlderThanTheRetentionArePurged() {
        UUID userId = UUID.randomUUID();
        writer.write(measure(userId, Instant.now().minus(Duration.ofDays(200))));
        writer.write(measure(userId, Instant.now().minus(Duration.ofDays(10))));

        writer.purgeOlderThan(Instant.now().minus(Duration.ofDays(TurnMeasureWriter.RETENTION_DAYS)));

        assertThat(count(userId)).isEqualTo(1);
    }

    @Test
    void aWriteFailureNeverReachesTheCaller() {
        TurnMeasureWriter broken = new TurnMeasureWriter(new JdbcTemplate()); // sans source : échoue
        broken.write(measure(UUID.randomUUID(), Instant.now()));
    }

    private int count(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM atelier_turn_measures WHERE user_id = ?", Integer.class, userId);
    }
}
