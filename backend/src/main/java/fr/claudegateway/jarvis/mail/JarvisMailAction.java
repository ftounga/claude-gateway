package fr.claudegateway.jarvis.mail;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Une action de courrier qui engage</b> (F-242 / SF-242-06, D9 c) — en attente d'un « oui » de l'utilisateur, vérifié
 * par la passerelle. {@link #payload} scellé ; {@link #summary} = la reformulation dite.
 */
@Entity
@Table(name = "jarvis_mail_actions")
@Getter
@Setter
@NoArgsConstructor
public class JarvisMailAction {

    public enum Kind {
        SEND, DELETE, ARCHIVE_BULK
    }

    public enum Status {
        PENDING, CONFIRMED, EXECUTED, CANCELLED, EXPIRED, FAILED
    }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    /** Le fil Jarvis où l'action a été proposée : seul un message de CE fil peut la confirmer. */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @Column(name = "payload", nullable = false, length = 64000)
    private String payload;

    @Column(name = "summary", nullable = false, length = 600)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "confirmed_by_message_id")
    private UUID confirmedByMessageId;

    @Column(name = "executed_at")
    private OffsetDateTime executedAt;

    @Column(name = "error", length = 300)
    private String error;
}
