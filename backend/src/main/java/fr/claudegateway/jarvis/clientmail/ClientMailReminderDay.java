package fr.claudegateway.jarvis.clientmail;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** <b>Les rappels d'un jour pour un client</b> (F-249 / SF-249-05) : combien sont partis, et le dépôt qui les fait taire. */
@Entity
@Table(name = "jarvis_client_mail_reminders")
@Getter
@Setter
@NoArgsConstructor
public class ClientMailReminderDay {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "reminder_day", nullable = false, updatable = false)
    private LocalDate reminderDay;

    @Column(name = "sent_count", nullable = false)
    private int sentCount;

    @Column(name = "last_sent_at")
    private OffsetDateTime lastSentAt;

    @Column(name = "deposited_at")
    private OffsetDateTime depositedAt;
}
