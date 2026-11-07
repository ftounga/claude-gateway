package fr.claudegateway.jarvis.clientmail;

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

/**
 * <b>Une réunion d'un client</b> (F-249 / SF-249-04, D14) — une occurrence d'invitation Outlook/Teams lue dans un mail
 * déposé : heures en clair (index), titre/lieu/description <b>scellés</b> (même JSON {@code EventText} que F-243).
 */
@Entity
@Table(name = "jarvis_client_meetings")
@Getter
@Setter
@NoArgsConstructor
public class ClientMeeting {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "ical_uid", nullable = false, length = 500)
    private String icalUid;

    @Column(name = "recurrence_id", length = 64)
    private String recurrenceId;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Column(name = "all_day", nullable = false)
    private boolean allDay;

    @Column(name = "sealed", nullable = false, length = 4000)
    private String sealed;

    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "source_received_at", nullable = false)
    private OffsetDateTime sourceReceivedAt;

    @Column(name = "mail_item_id")
    private UUID mailItemId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
