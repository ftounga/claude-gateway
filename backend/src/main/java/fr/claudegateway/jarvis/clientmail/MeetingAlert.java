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

/** <b>Une alerte avant réunion déjà donnée</b> (F-249 / SF-249-04, D15) — clé {@code UID|début}, unique par compte. */
@Entity
@Table(name = "jarvis_meeting_alerts")
@Getter
@Setter
@NoArgsConstructor
public class MeetingAlert {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "alert_key", nullable = false, updatable = false, length = 600)
    private String alertKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
