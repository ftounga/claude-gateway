package fr.claudegateway.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * F-247 / SF-247-03 — la file sur un <b>vrai PostgreSQL</b> : prise en {@code FOR UPDATE SKIP LOCKED}, deux
 * consommateurs concurrents (deux « pods »), <b>aucun doublon</b>, aucun travail perdu ; la prise passe par l'index
 * partiel. Ignoré proprement sans démon Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class AsyncJobQueuePostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private AsyncJobQueue queue;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("deux consommateurs concurrents : 500 travaux, chacun pris une fois, aucun doublon")
    void twoConsumersNoDuplicate() throws Exception {
        assertThat(queue.postgres()).isTrue();
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 500; i++) {
            queue.enqueue(AsyncJobQueue.Request.of(user, "PGTEST", "{\"i\":" + i + "}")).orElseThrow();
        }
        Set<UUID> seen = ConcurrentHashMap.newKeySet();
        List<UUID> duplicates = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pods = Executors.newFixedThreadPool(4);
        List<Future<?>> runs = new ArrayList<>();
        for (int p = 0; p < 4; p++) {
            String owner = "pod-" + p;
            runs.add(pods.submit(() -> {
                start.await();
                while (true) {
                    List<AsyncJobQueue.Job> jobs = queue.claim("PGTEST", 7, owner);
                    if (jobs.isEmpty()) {
                        return null;
                    }
                    for (AsyncJobQueue.Job j : jobs) {
                        if (!seen.add(j.id())) {
                            duplicates.add(j.id());
                        }
                        queue.complete(j.id(), owner, 1, null);
                    }
                }
            }));
        }
        start.countDown();
        for (Future<?> f : runs) {
            f.get(60, TimeUnit.SECONDS);
        }
        pods.shutdown();
        assertThat(duplicates).isEmpty();
        assertThat(seen).hasSize(500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM async_jobs WHERE kind = 'PGTEST' AND status = 'DONE'",
                Integer.class)).isEqualTo(500);

        // L'index partiel est utilisable par la prise (planificateur forcé hors parcours séquentiel, même connexion).
        String plan = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c -> {
            try (var st = c.createStatement()) {
                st.execute("SET enable_seqscan = off");
                StringBuilder sb = new StringBuilder();
                try (var rs = st.executeQuery("EXPLAIN SELECT id FROM async_jobs WHERE status = 'PENDING' "
                        + "AND kind = 'PGTEST' AND available_at <= now() ORDER BY priority, available_at LIMIT 5")) {
                    while (rs.next()) {
                        sb.append(rs.getString(1)).append('\n');
                    }
                }
                st.execute("RESET enable_seqscan");
                return sb.toString();
            }
        });
        assertThat(plan).contains("idx_async_jobs_pending");
    }
}
