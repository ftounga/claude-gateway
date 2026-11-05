package fr.claudegateway.jarvis.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

import fr.claudegateway.jarvis.portrait.PortraitSeal;

/**
 * F-247 / SF-247-02 — porte Z10 sur un <b>vrai PostgreSQL</b> : à 100 000 faits, la lecture des 20 plus récents d'un
 * client, d'une famille et d'un préfixe de clé tient en ≤ 10 ms au p95, par index (aucun parcours séquentiel).
 * Ignoré proprement sans démon Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class JarvisStateRegistryPostgresLoadTest {

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

    @Autowired private JarvisStateRegistry registry;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PortraitSeal seal;
    @Autowired private fr.claudegateway.user.UserRepository users;
    @Autowired private fr.claudegateway.runner.host.RunnerHostRepository hosts;

    @Test
    @DisplayName("Z10 — 100 000 faits : recent / byKind / byKey ≤ 10 ms p95, par index")
    void readsStayIndexedAt100k() {
        UUID user = UUID.randomUUID();
        List<UUID> hostIds = new ArrayList<>();
        UUID userId = users.save(fr.claudegateway.user.User.builder().email("load-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(fr.claudegateway.user.AuthProvider.LOCAL)
                .role(fr.claudegateway.user.UserRole.USER).createdAt(java.time.OffsetDateTime.now()).build()).getId();
        for (int h = 0; h < 10; h++) {
            hostIds.add(hosts.save(fr.claudegateway.runner.host.RunnerHost.builder().userId(userId).name("H" + h)
                    .rootName("dev").os("linux").shell("posix").elevated(false).build()).getId());
        }
        String sealed = seal.seal("En revue");
        String[] kinds = {"TICKET", "MR", "PIPELINE", "DEPLOYMENT", "GIT_BRANCH"};
        Instant base = Instant.now().minusSeconds(86_400);
        for (int h = 0; h < hostIds.size(); h++) {
            UUID hostId = hostIds.get(h);
            List<Object[]> rows = new ArrayList<>(10_000);
            for (int i = 0; i < 10_000; i++) {
                Timestamp at = Timestamp.from(base.plusSeconds(i));
                rows.add(new Object[] {UUID.randomUUID(), userId, hostId, kinds[i % kinds.length],
                        "JIRA:P" + h + "-" + i, "status", sealed, at, "TOUR", "HAUTE", at, at});
            }
            jdbc.batchUpdate("INSERT INTO jarvis_state_facts (id, user_id, host_id, object_kind, object_key, "
                    + "attribute, value_sealed, observed_at, source, confidence, created_at, updated_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", rows);
        }
        jdbc.execute("ANALYZE jarvis_state_facts");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jarvis_state_facts", Long.class))
                .isGreaterThanOrEqualTo(100_000L);

        UUID target = hostIds.get(3);
        // chauffe
        for (int i = 0; i < 30; i++) {
            registry.recent(userId, target, 20);
            registry.byKind(userId, target, "MR", 20);
            registry.byKey(userId, target, "JIRA:P3-99", 20);
        }
        double recentMs = p95(() -> registry.recent(userId, target, 20));
        double kindMs = p95(() -> registry.byKind(userId, target, "MR", 20));
        double keyMs = p95(() -> registry.byKey(userId, target, "JIRA:P3-99", 20));
        System.out.println("Z10 p95 ms recent=" + recentMs + " kind=" + kindMs + " key=" + keyMs);
        assertThat(recentMs).isLessThanOrEqualTo(10.0);
        assertThat(kindMs).isLessThanOrEqualTo(10.0);
        assertThat(keyMs).isLessThanOrEqualTo(10.0);
        assertThat(registry.recent(userId, target, 20)).hasSize(20);
        assertThat(registry.byKey(userId, target, "JIRA:P3-99", 20)).isNotEmpty();
        assertThat(registry.recent(user, target, 20)).isEmpty();

        String plan = String.join("\n", jdbc.queryForList("EXPLAIN SELECT * FROM jarvis_state_facts WHERE user_id = '"
                + userId + "' AND host_id = '" + target + "' ORDER BY observed_at DESC LIMIT 20", String.class));
        assertThat(plan).doesNotContain("Seq Scan");
        String prefixPlan = String.join("\n", jdbc.queryForList("EXPLAIN SELECT * FROM jarvis_state_facts WHERE "
                + "user_id = '" + userId + "' AND host_id = '" + target + "' AND object_key LIKE 'JIRA:P3-99%' "
                + "ORDER BY object_key, attribute LIMIT 20", String.class));
        assertThat(prefixPlan).doesNotContain("Seq Scan");
    }

    private static double p95(Runnable call) {
        List<Double> ms = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long t = System.nanoTime();
            call.run();
            ms.add((System.nanoTime() - t) / 1_000_000.0);
        }
        ms.sort(Double::compare);
        return ms.get((int) Math.ceil(0.95 * ms.size()) - 1);
    }
}
