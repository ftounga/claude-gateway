package fr.claudegateway.notifications;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * <b>Le verrou du récapitulatif</b> (F-185 / SF-185-07) : une ligne {@code (user_id, digest_day)}
 * par compte et par jour. Les deux pods lancent la tâche ; une seule insertion réussit, une seule
 * notification part. Chaque instruction vit dans sa propre transaction (pas de transaction englobante) :
 * un conflit sur PostgreSQL n'empoisonne rien.
 */
@Component
public class NotificationDigestWriter {

    private final JdbcTemplate jdbcTemplate;

    public NotificationDigestWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@code true} si ce compte n'avait pas encore eu son récapitulatif ce jour-là. */
    public boolean claim(UUID userId, LocalDate day, OffsetDateTime now) {
        try {
            return jdbcTemplate.update(
                    "insert into notification_digests (user_id, digest_day, created_at) values (?, ?, ?)",
                    userId, day, now) == 1;
        } catch (DuplicateKeyException alreadySent) {
            return false;
        }
    }

    /** Rétention : 30 jours. */
    public int purgeBefore(LocalDate day) {
        return jdbcTemplate.update("delete from notification_digests where digest_day < ?", day);
    }

    /** Suppression du compte. */
    public int deleteByUserId(UUID userId) {
        return jdbcTemplate.update("delete from notification_digests where user_id = ?", userId);
    }
}
