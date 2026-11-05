package fr.claudegateway.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** F-247 / SF-247-03 — la file durable sous H2 (repli conditionnel) : prise, bail, recul, faucheur, plafond, purge. */
@SpringBootTest
@ActiveProfiles("test")
class AsyncJobQueueIntegrationTest {

    @Autowired private AsyncJobQueue queue;
    @Autowired private JdbcTemplate jdbc;

    private String kind;
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        kind = "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM async_jobs WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("mise en file, prise unique, fin : DONE, image effacée, mesure par type")
    void lifecycle() {
        UUID id = queue.enqueue(new AsyncJobQueue.Request(user, kind, 100, "{\"m\":1}", new byte[] {1, 2, 3}, 3))
                .orElseThrow();
        List<AsyncJobQueue.Job> a = queue.claim(kind, 5, "pod-a");
        assertThat(a).hasSize(1);
        assertThat(a.get(0).payloadBytes()).containsExactly(1, 2, 3);
        assertThat(a.get(0).attempts()).isEqualTo(1);
        assertThat(queue.claim(kind, 5, "pod-b")).isEmpty();
        assertThat(queue.complete(id, "pod-b", 5, BigDecimal.ONE)).isFalse();
        assertThat(queue.complete(id, "pod-a", 12, new BigDecimal("0.002"))).isTrue();
        assertThat(status(id)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT payload_bytes FROM async_jobs WHERE id = ?", byte[].class, id))
                .isNull();
        assertThat(queue.measure(user, Instant.now().minusSeconds(60)).get(kind).byStatus()).containsEntry("DONE", 1L);
        assertThat(queue.measure(UUID.randomUUID(), Instant.now().minusSeconds(60))).doesNotContainKey(kind);
    }

    @Test
    @DisplayName("priorité puis ancienneté ; échec réessayable ⇒ recul ; au-delà des essais ⇒ FAILED, image effacée")
    void retryAndFail() {
        UUID low = queue.enqueue(new AsyncJobQueue.Request(user, kind, 200, null, null, 2)).orElseThrow();
        UUID high = queue.enqueue(new AsyncJobQueue.Request(user, kind, 10, null, new byte[] {9}, 2)).orElseThrow();
        assertThat(queue.claim(kind, 1, "p").get(0).id()).isEqualTo(high);
        assertThat(queue.fail(high, "p", "boom", true, 3, null)).isTrue();
        assertThat(status(high)).isEqualTo("PENDING");
        // recul : pas repris tout de suite
        assertThat(queue.claim(kind, 5, "p")).extracting(AsyncJobQueue.Job::id).containsExactly(low);
        jdbc.update("UPDATE async_jobs SET available_at = ? WHERE id = ?", Timestamp.from(Instant.now()
                .minusSeconds(1)), high);
        assertThat(queue.claim(kind, 5, "p")).extracting(AsyncJobQueue.Job::id).containsExactly(high);
        assertThat(queue.fail(high, "p", "boom", true, 3, null)).isTrue();
        assertThat(status(high)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT payload_bytes FROM async_jobs WHERE id = ?", byte[].class, high))
                .isNull();
        assertThat(AsyncJobQueue.backoff(1)).isEqualTo(Duration.ofSeconds(15));
        assertThat(AsyncJobQueue.backoff(3)).isEqualTo(Duration.ofSeconds(60));
        assertThat(AsyncJobQueue.backoff(30)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("faucheur : bail expiré ⇒ en attente ; plafond par type ⇒ DROPPED ; purge des DONE > 7 j")
    void reapCapPurge() {
        UUID id = queue.enqueue(AsyncJobQueue.Request.of(user, kind, null)).orElseThrow();
        queue.claim(kind, 1, "dead-pod");
        jdbc.update("UPDATE async_jobs SET lease_until = ? WHERE id = ?", Timestamp.from(Instant.now()
                .minusSeconds(5)), id);
        assertThat(queue.reap()).isGreaterThanOrEqualTo(1);
        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(queue.claim(kind, 1, "live-pod")).hasSize(1);

        queue.setPendingCap(kind, 1);
        Optional<UUID> first = queue.enqueue(AsyncJobQueue.Request.of(user, kind, null));
        Optional<UUID> second = queue.enqueue(AsyncJobQueue.Request.of(user, kind, null));
        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM async_jobs WHERE kind = ? AND status = 'DROPPED'",
                Integer.class, kind)).isEqualTo(1);

        queue.claim(kind, 1, "p");
        queue.complete(first.get(), "p", 1, null);
        jdbc.update("UPDATE async_jobs SET finished_at = ? WHERE id = ?", Timestamp.from(Instant.now()
                .minus(Duration.ofDays(8))), first.get());
        queue.purge();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM async_jobs WHERE id = ?", Integer.class, first.get()))
                .isZero();
    }

    @Test
    @DisplayName("le fil de travail : DONE avec coût, abandon, exception ⇒ réessai ; jamais dans le fil appelant")
    void workerRuns() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String callerThread = Thread.currentThread().getName();
        java.util.concurrent.CompletableFuture<String> ranOn = new java.util.concurrent.CompletableFuture<>();
        AsyncJobHandler h = new AsyncJobHandler() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public Outcome handle(AsyncJobQueue.Job job) {
                ranOn.complete(Thread.currentThread().getName());
                return switch (calls.incrementAndGet()) {
                    case 1 -> Outcome.done(new BigDecimal("0.01"));
                    case 2 -> Outcome.drop("cap");
                    default -> throw new IllegalStateException("x");
                };
            }
        };
        AsyncJobWorker worker = new AsyncJobWorker(queue, List.of(h), true);
        UUID a = queue.enqueue(AsyncJobQueue.Request.of(user, kind, null)).orElseThrow();
        worker.drain(h);
        assertThat(ranOn.get(5, java.util.concurrent.TimeUnit.SECONDS)).isNotEqualTo(callerThread).startsWith("job-");
        await(() -> "DONE".equals(status(a)));
        UUID b = queue.enqueue(AsyncJobQueue.Request.of(user, kind, null)).orElseThrow();
        worker.drain(h);
        await(() -> "DROPPED".equals(status(b)));
        UUID c = queue.enqueue(AsyncJobQueue.Request.of(user, kind, null)).orElseThrow();
        worker.drain(h);
        await(() -> "PENDING".equals(status(c)) && jdbc.queryForObject(
                "SELECT attempts FROM async_jobs WHERE id = ?", Integer.class, c) == 1);
        worker.stop();
    }

    private static void await(java.util.function.BooleanSupplier ok) throws InterruptedException {
        long until = System.currentTimeMillis() + 5_000;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("délai dépassé");
            }
            Thread.sleep(20);
        }
    }
}
