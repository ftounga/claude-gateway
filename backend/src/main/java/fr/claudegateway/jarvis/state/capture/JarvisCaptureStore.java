package fr.claudegateway.jarvis.state.capture;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateSettings;

/**
 * <b>Les transcriptions des captures de conversations</b> (F-247 / SF-247-12, cadrage §4.6) : deux tables,
 * {@code jarvis_conversation_captures} et {@code jarvis_capture_lines}, lues et écrites <b>sous {@code user_id} +
 * {@code host_id}</b>, toujours par index et avec une limite. Rétention 90 j par défaut (réglable par client), purge
 * nocturne, et purge complète quand le client est coupé ou effacé (« effacer tout » du registre).
 */
@Repository
public class JarvisCaptureStore {

    private static final Logger log = LoggerFactory.getLogger(JarvisCaptureStore.class);
    public static final int MAX_READ = 20;

    /** Une ligne écrite (pour la vectorisation, SF-247-13). */
    public record StoredLine(UUID id, UUID userId, UUID hostId, UUID workspaceId, String speaker, String text) {
    }

    /** Une ligne lue. */
    public record LineView(UUID id, UUID captureId, UUID workspaceId, String platform, String title,
            OffsetDateTime depositedAt, String speaker, boolean speakerKnown, OffsetDateTime saidAt, boolean approx,
            String text) {
    }

    /** Une capture lue (l'écran « Ce que je sais »). */
    public record CaptureView(UUID id, UUID workspaceId, String platform, String title, OffsetDateTime depositedAt,
            String status, int lineCount) {
    }

    /** Une ligne à écrire. */
    public record NewLine(String speaker, boolean speakerKnown, OffsetDateTime saidAt, boolean approx, String text) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final List<Consumer<List<StoredLine>>> listeners = new CopyOnWriteArrayList<>();

    public JarvisCaptureStore(JdbcTemplate jdbc, Clock clock, JarvisStateRegistry registry) {
        this.jdbc = jdbc;
        this.clock = clock;
        registry.onErase(this::eraseAll);
    }

    /** S'abonne aux lignes écrites (vectorisation, SF-247-13). */
    public void onLines(Consumer<List<StoredLine>> listener) {
        listeners.add(listener);
    }

    /** Le dépôt a-t-il déjà été lu (travail rejoué) ? */
    public boolean alreadyRead(UUID userId, UUID depositId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM jarvis_conversation_captures WHERE user_id = ? "
                + "AND deposit_id = ?", Integer.class, userId, depositId);
        return n != null && n > 0;
    }

    /** Les lectures du jour (plafond de 60 par jour), toutes issues confondues. */
    public int readsSince(UUID userId, Instant since) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM jarvis_conversation_captures WHERE user_id = ? "
                + "AND created_at >= ?", Integer.class, userId, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    /**
     * Range une lecture (statut, coût) et ses lignes, en une transaction. Rien n'est écrit en double pour un même
     * dépôt. Rend les lignes écrites.
     */
    @Transactional
    public List<StoredLine> save(UUID userId, UUID hostId, UUID workspaceId, UUID depositId, String platform,
            String title, OffsetDateTime depositedAt, String status, BigDecimal costUsd, List<NewLine> lines) {
        UUID captureId = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        try {
            jdbc.update("INSERT INTO jarvis_conversation_captures (id, user_id, host_id, workspace_id, deposit_id, "
                    + "platform, title, deposited_at, status, line_count, cost_usd, created_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", captureId, userId, hostId, workspaceId, depositId,
                    platform == null ? "AUTRE" : platform, title, Timestamp.from(depositedAt.toInstant()), status,
                    lines.size(), costUsd == null ? BigDecimal.ZERO : costUsd, now);
        } catch (DuplicateKeyException replay) {
            return List.of();
        }
        List<StoredLine> stored = new ArrayList<>();
        int position = 0;
        for (NewLine l : lines) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO jarvis_capture_lines (id, capture_id, user_id, host_id, workspace_id, position, "
                    + "speaker, speaker_known, said_at, said_at_approx, text, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, captureId, userId, hostId, workspaceId, position++, l.speaker(), l.speakerKnown(),
                    Timestamp.from(l.saidAt().toInstant()), l.approx(), l.text(), now);
            stored.add(new StoredLine(id, userId, hostId, workspaceId, l.speaker(), l.text()));
        }
        if (!stored.isEmpty()) {
            for (Consumer<List<StoredLine>> c : listeners) {
                try {
                    c.accept(List.copyOf(stored));
                } catch (RuntimeException e) {
                    log.warn("Captures : abonné en échec ({})", e.getClass().getSimpleName());
                }
            }
        }
        return stored;
    }

    /** Les lignes les plus récentes du client (index {@code user_id, host_id, said_at desc}). */
    public List<LineView> recentLines(UUID userId, UUID hostId, int limit) {
        return jdbc.query(LINE_SELECT + " WHERE l.user_id = ? AND l.host_id = ? ORDER BY l.said_at DESC LIMIT ?",
                (rs, i) -> line(rs), userId, hostId, Math.max(1, Math.min(limit, MAX_READ)));
    }

    /** Les lignes demandées par identifiant (résultat d'une recherche), restreintes au compte et au client. */
    public List<LineView> linesByIds(UUID userId, UUID hostId, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<UUID> bounded = ids.size() > MAX_READ ? ids.subList(0, MAX_READ) : ids;
        String in = String.join(",", java.util.Collections.nCopies(bounded.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(hostId);
        args.addAll(bounded);
        List<LineView> rows = jdbc.query(LINE_SELECT + " WHERE l.user_id = ? AND l.host_id = ? AND l.id IN (" + in + ")",
                (rs, i) -> line(rs), args.toArray());
        rows.sort(java.util.Comparator.comparingInt(r -> bounded.indexOf(r.id())));
        return rows;
    }

    /** Les captures récentes du client (écran). */
    public List<CaptureView> recentCaptures(UUID userId, UUID hostId, int limit) {
        return jdbc.query("SELECT id, workspace_id, platform, title, deposited_at, status, line_count FROM "
                + "jarvis_conversation_captures WHERE user_id = ? AND host_id = ? ORDER BY deposited_at DESC LIMIT ?",
                (rs, i) -> new CaptureView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), at(rs.getTimestamp(5)), rs.getString(6), rs.getInt(7)),
                userId, hostId, Math.max(1, Math.min(limit, MAX_READ)));
    }

    /** Le nombre de captures lues du client. */
    public int count(UUID userId, UUID hostId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM jarvis_conversation_captures WHERE user_id = ? "
                + "AND host_id = ? AND status = 'LUE'", Integer.class, userId, hostId);
        return n == null ? 0 : n;
    }

    /** Efface une capture du compte (et ses lignes). */
    @Transactional
    public boolean delete(UUID userId, UUID captureId) {
        jdbc.update("DELETE FROM jarvis_capture_lines WHERE user_id = ? AND capture_id = ?", userId, captureId);
        return jdbc.update("DELETE FROM jarvis_conversation_captures WHERE user_id = ? AND id = ?", userId,
                captureId) == 1;
    }

    /** Couper ou effacer un client : toutes ses transcriptions. */
    @Transactional
    public int eraseAll(UUID userId, UUID hostId) {
        jdbc.update("DELETE FROM jarvis_capture_lines WHERE user_id = ? AND host_id = ?", userId, hostId);
        return jdbc.update("DELETE FROM jarvis_conversation_captures WHERE user_id = ? AND host_id = ?", userId, hostId);
    }

    /** Purge nocturne : au-delà de la rétention du client (90 j par défaut). */
    @Scheduled(cron = "${app.jarvis.state.capture-purge-cron:0 50 3 * * *}", zone = "Europe/Paris")
    @Transactional
    public int purge() {
        Instant now = clock.instant();
        int n = 0;
        List<Object[]> custom = jdbc.query("SELECT user_id, host_id, capture_retention_days FROM jarvis_state_settings "
                + "WHERE capture_retention_days <> ?", (rs, i) -> new Object[] {rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getInt(3)}, JarvisStateSettings.DEFAULT_CAPTURE_RETENTION_DAYS);
        for (Object[] c : custom) {
            Timestamp edge = Timestamp.from(now.minus(java.time.Duration.ofDays(Math.max(1, (Integer) c[2]))));
            jdbc.update("DELETE FROM jarvis_capture_lines WHERE user_id = ? AND host_id = ? AND capture_id IN (SELECT id "
                    + "FROM jarvis_conversation_captures WHERE user_id = ? AND host_id = ? AND deposited_at < ?)",
                    c[0], c[1], c[0], c[1], edge);
            n += jdbc.update("DELETE FROM jarvis_conversation_captures WHERE user_id = ? AND host_id = ? AND "
                    + "deposited_at < ?", c[0], c[1], edge);
        }
        Timestamp edge = Timestamp.from(now.minus(java.time.Duration.ofDays(
                JarvisStateSettings.DEFAULT_CAPTURE_RETENTION_DAYS)));
        String defaults = "deposited_at < ? AND NOT EXISTS (SELECT 1 FROM jarvis_state_settings s WHERE s.user_id = "
                + "c.user_id AND s.host_id = c.host_id AND s.capture_retention_days <> ?)";
        jdbc.update("DELETE FROM jarvis_capture_lines WHERE capture_id IN (SELECT c.id FROM jarvis_conversation_captures c "
                + "WHERE " + defaults + ")", edge, JarvisStateSettings.DEFAULT_CAPTURE_RETENTION_DAYS);
        n += jdbc.update("DELETE FROM jarvis_conversation_captures c WHERE " + defaults, edge,
                JarvisStateSettings.DEFAULT_CAPTURE_RETENTION_DAYS);
        return n;
    }

    private static final String LINE_SELECT = "SELECT l.id, l.capture_id, l.workspace_id, c.platform, c.title, "
            + "c.deposited_at, l.speaker, l.speaker_known, l.said_at, l.said_at_approx, l.text FROM jarvis_capture_lines l "
            + "JOIN jarvis_conversation_captures c ON c.id = l.capture_id";

    private static LineView line(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LineView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getString(5), at(rs.getTimestamp(6)), rs.getString(7), rs.getBoolean(8),
                at(rs.getTimestamp(9)), rs.getBoolean(10), rs.getString(11));
    }

    private static OffsetDateTime at(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }
}
