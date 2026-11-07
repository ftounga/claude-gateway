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

/** <b>Un courrier déposé rattaché à un sujet</b> (F-249 / SF-249-03, D9) — sans IA ; raison {@code FIL}, {@code TICKET}, {@code MOTS}. */
@Entity
@Table(name = "jarvis_mail_subject_links")
@Getter
@Setter
@NoArgsConstructor
public class ClientMailSubjectLink {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "reason", nullable = false, length = 8)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
