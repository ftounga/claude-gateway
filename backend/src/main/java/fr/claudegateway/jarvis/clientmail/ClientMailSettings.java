package fr.claudegateway.jarvis.clientmail;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** <b>Les réglages des mails des clients</b> (F-249 / SF-249-05, D12, D15) — un par compte ; défauts si absent. */
@Entity
@Table(name = "jarvis_client_mail_settings")
@Getter
@Setter
@NoArgsConstructor
public class ClientMailSettings {

    public static final String DEFAULT_TIME = "18:30";
    public static final int DEFAULT_MAX = 3;
    public static final int DEFAULT_EVERY = 45;
    public static final int DEFAULT_MEETING_ALERT = 10;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "reminder_time", nullable = false, length = 5)
    private String reminderTime = DEFAULT_TIME;

    @Column(name = "reminder_max", nullable = false)
    private int reminderMax = DEFAULT_MAX;

    @Column(name = "reminder_every_minutes", nullable = false)
    private int reminderEveryMinutes = DEFAULT_EVERY;

    @Column(name = "weekdays_only", nullable = false)
    private boolean weekdaysOnly = true;

    @Column(name = "meeting_alert_minutes", nullable = false)
    private int meetingAlertMinutes = DEFAULT_MEETING_ALERT;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Les réglages par défaut d'un compte (non enregistrés). */
    public static ClientMailSettings defaults(UUID userId) {
        ClientMailSettings s = new ClientMailSettings();
        s.setUserId(userId);
        return s;
    }
}
