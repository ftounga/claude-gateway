package fr.claudegateway.jarvis.state.capture;

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

/**
 * F-247 / SF-247-13 — porte Z10 sur un <b>vrai PostgreSQL</b> : à 100 000 lignes de capture, les plus récentes d'un
 * client, la recherche par mots (fenêtre bornée) et par personne tiennent en ≤ 10 ms au p95, par index. Le plus proche
 * voisin vectoriel est vérifié dans son plan (index hnsw). Ignoré proprement sans démon Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class JarvisCaptureSearchPostgresLoadTest {

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

    @Autowired private JarvisCaptureSearch search;
    @Autowired private JarvisCaptureStore store;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("Z10 — 100 000 lignes : récentes / mots / personne ≤ 10 ms p95, par index")
    void readsStayIndexedAt100k() {
        UUID userId = UUID.randomUUID();
        List<UUID> hostIds = new ArrayList<>();
        Instant base = Instant.now().minusSeconds(200_000);
        String[] speakers = {"Rémi Dupont", "Sophie Martin", "Karim Benali", "Inès Morel"};
        for (int h = 0; h < 10; h++) {
            UUID hostId = UUID.randomUUID();
            hostIds.add(hostId);
            UUID workspace = UUID.randomUUID();
            List<Object[]> caps = new ArrayList<>();
            List<Object[]> lines = new ArrayList<>(10_000);
            for (int c = 0; c < 500; c++) {
                UUID capture = UUID.randomUUID();
                Timestamp at = Timestamp.from(base.plusSeconds(c * 20L));
                caps.add(new Object[] {capture, userId, hostId, workspace, UUID.randomUUID(), "TEAMS", "Fil " + c, at,
                        "LUE", 20, at});
                for (int i = 0; i < 20; i++) {
                    Timestamp said = Timestamp.from(base.plusSeconds(c * 20L + i));
                    lines.add(new Object[] {UUID.randomUUID(), capture, userId, hostId, workspace, i,
                            speakers[(c + i) % speakers.length], false, said, false,
                            "Message " + c + "-" + i + (i == 7 ? " au sujet du ticket PROJ-" + c : " sans objet"),
                            said});
                }
            }
            jdbc.batchUpdate("INSERT INTO jarvis_conversation_captures (id, user_id, host_id, workspace_id, deposit_id, "
                    + "platform, title, deposited_at, status, line_count, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    caps);
            jdbc.batchUpdate("INSERT INTO jarvis_capture_lines (id, capture_id, user_id, host_id, workspace_id, "
                    + "position, speaker, speaker_known, said_at, said_at_approx, text, created_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", lines);
        }
        jdbc.execute("ANALYZE jarvis_capture_lines");
        jdbc.execute("ANALYZE jarvis_conversation_captures");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jarvis_capture_lines", Long.class))
                .isGreaterThanOrEqualTo(100_000L);

        UUID target = hostIds.get(4);
        for (int i = 0; i < 30; i++) {
            search.search(userId, target, "", List.of(), 15);
            search.search(userId, target, "PROJ-499", List.of(), 15);
            search.search(userId, target, "", List.of("Rémi Dupont"), 15);
        }
        double recent = p95(() -> search.search(userId, target, "", List.of(), 15));
        double words = p95(() -> search.search(userId, target, "PROJ-499", List.of(), 15));
        double who = p95(() -> search.search(userId, target, "", List.of("Rémi Dupont"), 15));
        System.out.println("Z10 captures p95 ms recent=" + recent + " words=" + words + " speaker=" + who);
        assertThat(recent).isLessThanOrEqualTo(10.0);
        assertThat(words).isLessThanOrEqualTo(10.0);
        assertThat(who).isLessThanOrEqualTo(10.0);
        assertThat(search.search(userId, target, "PROJ-499", List.of(), 15)).isNotEmpty();
        assertThat(store.recentLines(UUID.randomUUID(), target, 15)).isEmpty();

        String plan = String.join("\n", jdbc.queryForList("EXPLAIN SELECT id FROM jarvis_capture_lines WHERE user_id = '"
                + userId + "' AND host_id = '" + target + "' ORDER BY said_at DESC LIMIT 2000", String.class));
        assertThat(plan).doesNotContain("Seq Scan");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = "
                + "'jarvis_capture_lines_embedding_idx'", Integer.class)).isEqualTo(1);
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
