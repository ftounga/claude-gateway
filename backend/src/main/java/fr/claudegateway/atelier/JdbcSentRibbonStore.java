package fr.claudegateway.atelier;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Rubans envoyés en base, colonne {@code atelier_messages.sent_ribbon} (F-276 / SF-276-02). Best-effort :
 * une panne ne coûte jamais un tour, elle rend le rejeu d'avant.
 */
@Component
public class JdbcSentRibbonStore implements SentRibbonStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcSentRibbonStore.class);

    /** Borne d'une requête {@code IN} : la fenêtre tracée compte au plus 2 × 40 − 1 tours. */
    private static final int MAX_IDS = 200;

    private final JdbcTemplate jdbcTemplate;
    private final boolean replayEnabled;

    public JdbcSentRibbonStore(JdbcTemplate jdbcTemplate,
            @Value("${app.atelier.replay-sent-ribbon:true}") boolean replayEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.replayEnabled = replayEnabled;
    }

    @Override
    public boolean replayEnabled() {
        return replayEnabled;
    }

    @Override
    public void save(UUID userId, UUID messageId, String json) {
        if (userId == null || messageId == null || json == null) {
            return;
        }
        try {
            jdbcTemplate.update("UPDATE atelier_messages SET sent_ribbon = ? WHERE id = ? AND user_id = ?",
                    json, messageId, userId);
        } catch (RuntimeException e) {
            log.warn("Ruban envoyé non rangé (message={}) : {}", messageId, e.getMessage());
        }
    }

    @Override
    public Map<UUID, String> load(UUID userId, Collection<UUID> messageIds) {
        Map<UUID, String> ribbons = new HashMap<>();
        if (userId == null || messageIds == null || messageIds.isEmpty() || messageIds.size() > MAX_IDS) {
            return ribbons;
        }
        try {
            String marks = String.join(",", java.util.Collections.nCopies(messageIds.size(), "?"));
            Object[] args = new Object[messageIds.size() + 1];
            args[0] = userId;
            int i = 1;
            for (UUID id : messageIds) {
                args[i++] = id;
            }
            jdbcTemplate.query("SELECT id, sent_ribbon FROM atelier_messages WHERE user_id = ? AND id IN (" + marks
                    + ") AND sent_ribbon IS NOT NULL", rs -> {
                        ribbons.put(rs.getObject(1, UUID.class), rs.getString(2));
                    }, args);
        } catch (RuntimeException e) {
            log.debug("Rubans envoyés illisibles (best-effort) : {}", e.getMessage());
        }
        return ribbons;
    }

    @Override
    public int purgeBefore(UUID userId, UUID workspaceId, OffsetDateTime frontier) {
        if (userId == null || workspaceId == null || frontier == null) {
            return 0;
        }
        try {
            return jdbcTemplate.update("UPDATE atelier_messages SET sent_ribbon = NULL WHERE user_id = ? "
                    + "AND workspace_id = ? AND created_at < ? AND sent_ribbon IS NOT NULL",
                    userId, workspaceId, frontier);
        } catch (RuntimeException e) {
            log.debug("Rubans d'avant la frontière non purgés (best-effort) : {}", e.getMessage());
            return 0;
        }
    }
}
