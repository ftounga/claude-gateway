package fr.claudegateway.jarvis.state.sync;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * <b>Les passages de synchronisation</b> (F-247 / SF-247-16), table {@code jarvis_state_syncs} : la progression
 * persistée de chaque passage (relue par l'écran), son curseur (reprise après un redémarrage de pod) et sa mesure par
 * phase. Toute requête porte {@code user_id} ; un seul passage actif par client (contrainte {@code (user_id,
 * active_host)}).
 */
@Repository
public class JarvisStateSyncStore {

    private final JdbcTemplate jdbc;

    public JarvisStateSyncStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }


    /** SF-247-17 — la phase 4 (constats sur l'historique). */
    private static final String P4 = "p4_status, p4_total, p4_done, p4_facts, p4_kept, p4_rejected, p4_calls, "
            + "p4_errors, p4_est_chars, p4_est_calls, p4_ms, p4_cost_usd, p4_envelope_eur, p4_detail";

    private static final String COLUMNS = "id, user_id, host_id, status, phase, step, note, window_days, cursor_at, "
            + "cursor_id, waiting_since, p1_status, p1_total, p1_done, p1_facts, p1_ms, p1_detail, p2_status, p2_total, "
            + "p2_done, p2_facts, p2_read, p2_missing, p2_dropped, p2_ms, p2_cost_usd, p2_started_at, p2_detail, "
            + "p3_status, p3_total, p3_done, p3_facts, p3_ms, p3_started_at, p3_detail, started_at, updated_at, "
            + "finished_at, " + P4;

    private static Object[] p4(SyncRow s) {
        return new Object[] {s.getP4Status(), s.getP4Total(), s.getP4Done(), s.getP4Facts(), s.getP4Kept(),
                s.getP4Rejected(), s.getP4Calls(), s.getP4Errors(), s.getP4EstChars(), s.getP4EstCalls(), s.getP4Ms(),
                s.getP4CostUsd(), s.getP4EnvelopeEur(), s.getP4Detail()};
    }

    private static Object[] concat(Object[] head, Object... tail) {
        Object[] out = java.util.Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    /** Crée un passage actif. Lève {@code DuplicateKeyException} si un passage de ce client est déjà actif. */
    public void insert(SyncRow s) {
        Object[] base = {s.getId(), s.getUserId(), s.getHostId(), s.getStatus(),
                s.getPhase(), s.getStep(), s.getNote(), s.getWindowDays(), ts(s.getCursorAt()), s.getCursorId(),
                ts(s.getWaitingSince()), s.getP1Status(), s.getP1Total(), s.getP1Done(), s.getP1Facts(), s.getP1Ms(),
                s.getP1Detail(), s.getP2Status(), s.getP2Total(), s.getP2Done(), s.getP2Facts(), s.getP2Read(),
                s.getP2Missing(), s.getP2Dropped(), s.getP2Ms(), s.getP2CostUsd(), ts(s.getP2StartedAt()),
                s.getP2Detail(), s.getP3Status(), s.getP3Total(), s.getP3Done(), s.getP3Facts(), s.getP3Ms(),
                ts(s.getP3StartedAt()), s.getP3Detail(), ts(s.getStartedAt()), ts(s.getUpdatedAt()),
                ts(s.getFinishedAt())};
        Object[] args = concat(concat(base, p4(s)), s.getHostId());
        String marks = String.join(",", java.util.Collections.nCopies(args.length, "?"));
        jdbc.update("INSERT INTO jarvis_state_syncs (" + COLUMNS + ", active_host) VALUES (" + marks + ")", args);
    }

    /** Un passage du compte. */
    public Optional<SyncRow> find(UUID userId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM jarvis_state_syncs WHERE id = ? AND user_id = ?", ROW, id, userId)
                .stream().findFirst();
    }

    /** Le passage actif d'un client du compte. */
    public Optional<SyncRow> findActive(UUID userId, UUID hostId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM jarvis_state_syncs WHERE user_id = ? AND active_host = ?", ROW,
                userId, hostId).stream().findFirst();
    }

    /** Les passages récents du compte, les plus récents d'abord (index {@code user_id, host_id, started_at}). */
    public List<SyncRow> recent(UUID userId, OffsetDateTime since, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM jarvis_state_syncs WHERE user_id = ? AND started_at >= ? "
                + "ORDER BY started_at DESC LIMIT ?", ROW, userId, ts(since), Math.max(1, limit));
    }

    /**
     * Enregistre l'avancée d'une tranche et passe à l'étape suivante, <b>seulement</b> si le passage est encore actif
     * et à l'étape attendue (sinon : annulé, fini, ou travail périmé). Un passage terminé ({@code finishedAt} posé)
     * libère sa place ({@code active_host = NULL}).
     *
     * @return vrai si l'avancée est enregistrée
     */
    public boolean advance(SyncRow s, int expectedStep) {
        Object[] base = {s.getStatus(), s.getPhase(), expectedStep + 1, s.getNote(), ts(s.getCursorAt()),
                s.getCursorId(), ts(s.getWaitingSince()), s.getP1Status(), s.getP1Total(), s.getP1Done(),
                s.getP1Facts(), s.getP1Ms(), s.getP1Detail(), s.getP2Status(), s.getP2Total(), s.getP2Done(),
                s.getP2Facts(), s.getP2Read(), s.getP2Missing(), s.getP2Dropped(), s.getP2Ms(), s.getP2CostUsd(),
                ts(s.getP2StartedAt()), s.getP2Detail(), s.getP3Status(), s.getP3Total(), s.getP3Done(),
                s.getP3Facts(), s.getP3Ms(), ts(s.getP3StartedAt()), s.getP3Detail(), ts(s.getUpdatedAt()),
                ts(s.getFinishedAt()), s.getFinishedAt() == null ? s.getHostId() : null};
        Object[] args = concat(concat(base, p4(s)), s.getId(), s.getUserId(), expectedStep);
        return jdbc.update("UPDATE jarvis_state_syncs SET status = ?, phase = ?, step = ?, note = ?, cursor_at = ?, "
                + "cursor_id = ?, waiting_since = ?, p1_status = ?, p1_total = ?, p1_done = ?, p1_facts = ?, p1_ms = ?, "
                + "p1_detail = ?, p2_status = ?, p2_total = ?, p2_done = ?, p2_facts = ?, p2_read = ?, p2_missing = ?, "
                + "p2_dropped = ?, p2_ms = ?, p2_cost_usd = ?, p2_started_at = ?, p2_detail = ?, p3_status = ?, "
                + "p3_total = ?, p3_done = ?, p3_facts = ?, p3_ms = ?, p3_started_at = ?, p3_detail = ?, updated_at = ?, "
                + "finished_at = ?, active_host = ?, " + P4.replace(",", " = ?,") + " = ? "
                + "WHERE id = ? AND user_id = ? AND step = ? AND active_host IS NOT NULL", args) == 1;
    }

    /** Annule le passage actif d'un client ; rend son identifiant s'il y en avait un. */
    public Optional<UUID> cancel(UUID userId, UUID hostId, OffsetDateTime now) {
        Optional<SyncRow> active = findActive(userId, hostId);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        SyncRow s = active.get();
        int n = jdbc.update("UPDATE jarvis_state_syncs SET status = 'CANCELLED', note = NULL, active_host = NULL, "
                + "updated_at = ?, finished_at = ?, step = step + 1, "
                + "p1_status = CASE WHEN p1_status IN ('DONE', 'SKIPPED', 'EXPIRED') THEN p1_status ELSE 'CANCELLED' END, "
                + "p2_status = CASE WHEN p2_status IN ('DONE', 'SKIPPED', 'EXPIRED') THEN p2_status ELSE 'CANCELLED' END, "
                + "p3_status = CASE WHEN p3_status IN ('DONE', 'SKIPPED', 'EXPIRED') THEN p3_status ELSE 'CANCELLED' END, "
                + "p4_status = CASE WHEN p4_status IN ('DONE', 'SKIPPED', 'EXPIRED') THEN p4_status ELSE 'CANCELLED' END "
                + "WHERE id = ? AND user_id = ? AND active_host IS NOT NULL", ts(now), ts(now), s.getId(), userId);
        return n == 1 ? Optional.of(s.getId()) : Optional.empty();
    }

    /** Relance une chaîne perdue : le passage reprend à une nouvelle étape (rend la nouvelle étape, -1 sinon). */
    public int bumpStep(UUID userId, UUID id, int expectedStep, OffsetDateTime now) {
        int n = jdbc.update("UPDATE jarvis_state_syncs SET step = step + 1, updated_at = ? WHERE id = ? AND user_id = ? "
                + "AND step = ? AND active_host IS NOT NULL", ts(now), id, userId, expectedStep);
        return n == 1 ? expectedStep + 1 : -1;
    }

    /** Nombre de faits d'une source touchés depuis un instant, pour un client (index {@code observed_at}). */
    public int factsSince(UUID userId, UUID hostId, String source, OffsetDateTime since) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM jarvis_state_facts WHERE user_id = ? AND host_id = ? "
                + "AND observed_at >= ? AND source = ?", Integer.class, userId, hostId, ts(since), source);
        return n == null ? 0 : n;
    }

    private static Timestamp ts(OffsetDateTime t) {
        return t == null ? null : Timestamp.from(t.toInstant());
    }

    private static OffsetDateTime odt(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static final RowMapper<SyncRow> ROW = (rs, i) -> {
        SyncRow s = new SyncRow();
        s.setId(rs.getObject("id", UUID.class));
        s.setUserId(rs.getObject("user_id", UUID.class));
        s.setHostId(rs.getObject("host_id", UUID.class));
        s.setStatus(rs.getString("status"));
        s.setPhase(rs.getInt("phase"));
        s.setStep(rs.getInt("step"));
        s.setNote(rs.getString("note"));
        s.setWindowDays(rs.getInt("window_days"));
        s.setCursorAt(odt(rs, "cursor_at"));
        s.setCursorId(rs.getObject("cursor_id", UUID.class));
        s.setWaitingSince(odt(rs, "waiting_since"));
        s.setP1Status(rs.getString("p1_status"));
        s.setP1Total(rs.getInt("p1_total"));
        s.setP1Done(rs.getInt("p1_done"));
        s.setP1Facts(rs.getInt("p1_facts"));
        s.setP1Ms(rs.getLong("p1_ms"));
        s.setP1Detail(rs.getString("p1_detail"));
        s.setP2Status(rs.getString("p2_status"));
        s.setP2Total(rs.getInt("p2_total"));
        s.setP2Done(rs.getInt("p2_done"));
        s.setP2Facts(rs.getInt("p2_facts"));
        s.setP2Read(rs.getInt("p2_read"));
        s.setP2Missing(rs.getInt("p2_missing"));
        s.setP2Dropped(rs.getInt("p2_dropped"));
        s.setP2Ms(rs.getLong("p2_ms"));
        BigDecimal cost = rs.getBigDecimal("p2_cost_usd");
        s.setP2CostUsd(cost == null ? BigDecimal.ZERO : cost);
        s.setP2StartedAt(odt(rs, "p2_started_at"));
        s.setP2Detail(rs.getString("p2_detail"));
        s.setP3Status(rs.getString("p3_status"));
        s.setP3Total(rs.getInt("p3_total"));
        s.setP3Done(rs.getInt("p3_done"));
        s.setP3Facts(rs.getInt("p3_facts"));
        s.setP3Ms(rs.getLong("p3_ms"));
        s.setP3StartedAt(odt(rs, "p3_started_at"));
        s.setP3Detail(rs.getString("p3_detail"));
        s.setStartedAt(odt(rs, "started_at"));
        s.setUpdatedAt(odt(rs, "updated_at"));
        s.setFinishedAt(odt(rs, "finished_at"));
        s.setP4Status(rs.getString("p4_status"));
        s.setP4Total(rs.getInt("p4_total"));
        s.setP4Done(rs.getInt("p4_done"));
        s.setP4Facts(rs.getInt("p4_facts"));
        s.setP4Kept(rs.getInt("p4_kept"));
        s.setP4Rejected(rs.getInt("p4_rejected"));
        s.setP4Calls(rs.getInt("p4_calls"));
        s.setP4Errors(rs.getInt("p4_errors"));
        s.setP4EstChars(rs.getLong("p4_est_chars"));
        s.setP4EstCalls(rs.getInt("p4_est_calls"));
        s.setP4Ms(rs.getLong("p4_ms"));
        BigDecimal p4Cost = rs.getBigDecimal("p4_cost_usd");
        s.setP4CostUsd(p4Cost == null ? BigDecimal.ZERO : p4Cost);
        BigDecimal envelope = rs.getBigDecimal("p4_envelope_eur");
        s.setP4EnvelopeEur(envelope == null ? BigDecimal.ZERO : envelope);
        s.setP4Detail(rs.getString("p4_detail"));
        return s;
    };
}
