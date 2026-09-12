package fr.claudegateway.terminals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * L'instruction de <b>production</b>, jouée contre un <b>vrai</b> PostgreSQL (F-78 / SF-78-01).
 *
 * <p><b>Pourquoi ce test existe.</b> La production tourne sur PostgreSQL, les tests d'intégration
 * sur H2 — et les deux n'écrivent pas l'upsert pareil. H2 ne connaît même pas {@code ON CONFLICT} :
 * aucun test H2 ne peut donc prouver que la clause qui protège la production fait ce qu'on croit.
 * Ce test-ci exécute l'instruction <b>lue sur {@link LiveTerminalClaimWriter}</b>, jamais recopiée,
 * en {@value #CONCURRENT_CLAIMS} transactions concurrentes sur la <b>même</b>
 * {@code (user_id, session_id)}.</p>
 *
 * <p><b>Il se saute de lui-même</b> quand aucun PostgreSQL n'écoute (CI, poste sans Docker) : un
 * test d'environnement qui rougirait faute d'environnement n'apprendrait rien à personne. Pour le
 * jouer : {@code docker compose up -d} (voir {@code CLAUDE.md}).</p>
 */
class LiveTerminalClaimWriterPostgresTest {

    /** Le PostgreSQL de développement documenté dans {@code CLAUDE.md} / {@code docker-compose.yml}. */
    private static final String URL = "jdbc:postgresql://localhost:5432/claudegatewaydb";
    private static final String USER = "claudegateway";
    private static final String PASSWORD = "claudegateway";

    /** Assez de fils pour que la course ait lieu ; assez peu pour rester une seconde. */
    static final int CONCURRENT_CLAIMS = 24;

    /**
     * Table d'essai, à côté de la vraie : ce test valide une <b>instruction</b>, il n'a rien à
     * faire dans le registre d'un poste de développement.
     */
    private static final String TABLE = "live_terminals_f78_probe";

    private final String insertSql =
            LiveTerminalClaimWriter.insertSql("PostgreSQL").replace("live_terminals", TABLE);

    private static final String RENEW = "update " + TABLE
            + " set workspace_id = ?, last_seen_at = ? where user_id = ? and session_id = ?";

    @Test
    void theProductionStatementSurvivesConcurrentClaimsOfTheSameTab() throws Exception {
        assumeTrue(postgresIsListening(), "PostgreSQL absent (docker compose up -d) — test sauté");

        UUID user = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        createTable();
        try {
            List<String> outcomes = claimTogether(user, workspace, "tab-1");

            // ZÉRO ERREUR : aucun « duplicate key value violates unique constraint ».
            assertThat(outcomes).doesNotContain("erreur");
            // UNE SEULE PLACE, et UNE SEULE CRÉATION : le plafond ne s'arbitre qu'une fois.
            assertThat(outcomes).filteredOn("creation"::equals).hasSize(1);
            assertThat(rowCount()).isEqualTo(1);
            // Et le conflit a bien été ABSORBÉ par le moteur, pas rattrapé après coup : les
            // perdants ont vu « zéro ligne insérée », jamais une exception.
            assertThat(outcomes).contains("conflit puis renouvellement");
        } finally {
            dropTable();
        }
    }

    // ------------------------------------------------------------------ mécanique

    /** Le même enchaînement que {@code LiveTerminalService.claim} : renouveler, sinon prendre. */
    private String claimOnce(Connection connection, UUID user, UUID workspace, String session)
            throws SQLException {
        OffsetDateTime now = OffsetDateTime.now();
        if (renew(connection, user, workspace, session, now) > 0) {
            return "renouvellement";
        }
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, user);
            insert.setObject(3, workspace);
            insert.setString(4, session);
            insert.setObject(5, now);
            insert.setObject(6, now);
            insert.setString(7, TerminalActivity.RUNNING.name());
            insert.setString(8, "npm test");
            insert.setString(9, "PASS");
            insert.setObject(10, now);
            if (insert.executeUpdate() == 1) {
                return "creation";
            }
        }
        return renew(connection, user, workspace, session, now) > 0
                ? "conflit puis renouvellement"
                : "conflit sans place";
    }

    private static int renew(Connection connection, UUID user, UUID workspace, String session,
            OffsetDateTime now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(RENEW)) {
            update.setObject(1, workspace);
            update.setObject(2, now);
            update.setObject(3, user);
            update.setString(4, session);
            return update.executeUpdate();
        }
    }

    private List<String> claimTogether(UUID user, UUID workspace, String session) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CLAIMS);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<String>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < CONCURRENT_CLAIMS; i++) {
                pending.add(pool.submit(() -> {
                    gate.await();
                    try (Connection connection = connect()) {
                        connection.setAutoCommit(false);
                        String outcome = claimOnce(connection, user, workspace, session);
                        connection.commit();
                        return outcome;
                    } catch (SQLException e) {
                        return "erreur";
                    }
                }));
            }
            gate.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : pending) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static boolean postgresIsListening() {
        try (Connection connection = connect()) {
            return connection.isValid(2);
        } catch (SQLException unavailable) {
            return false;
        }
    }

    private void createTable() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists " + TABLE);
            // La forme EXACTE de la migration 071 + 075, index unique compris : c'est lui que
            // l'instruction doit apprivoiser.
            statement.execute("create table " + TABLE + " ("
                    + " id uuid primary key, user_id uuid not null, workspace_id uuid not null,"
                    + " session_id varchar(64) not null, opened_at timestamptz not null,"
                    + " last_seen_at timestamptz not null, activity varchar(24),"
                    + " activity_detail varchar(120), preview_lines varchar(1024),"
                    + " activity_at timestamptz)");
            statement.execute("create unique index idx_" + TABLE + "_user_session"
                    + " on " + TABLE + " (user_id, session_id)");
        }
    }

    private void dropTable() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists " + TABLE);
        }
    }

    private long rowCount() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select count(*) from " + TABLE)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
