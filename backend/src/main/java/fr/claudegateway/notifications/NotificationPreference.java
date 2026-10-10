package fr.claudegateway.notifications;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Les préférences de notification d'un compte (F-185 / SF-185-06). Clé : {@code user_id}. */
@Entity
@Table(name = "notification_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationPreference {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Codes {@link fr.claudegateway.push.PushEvent} coupés, séparés par des virgules. */
    @Column(name = "muted_events", length = 1000)
    private String mutedEvents;

    @Column(name = "quiet_from", length = 5)
    private String quietFrom;

    @Column(name = "quiet_to", length = 5)
    private String quietTo;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
