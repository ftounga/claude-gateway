package fr.claudegateway.mail;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Un courriel que l'utilisateur s'envoie</b> (F-110 / SF-110-02) : la file d'envoi <b>et</b> le journal.
 *
 * <p>Le destinataire est celui que la gateway a résolu à la mise en file ; les corps n'existent que le temps de
 * l'envoi et sont effacés à l'état final ({@link ClientEmailStatus#SENT} ou {@link ClientEmailStatus#FAILED}).</p>
 */
@Entity
@Table(name = "client_emails")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientEmail {

    public enum Kind {
        /** Demandé par l'agent ({@code email_me}). */
        AGENT,
        /** Le résumé du matin du Radar (SF-110-04). */
        MORNING_SUMMARY
    }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** Terminal d'où vient la demande ; nul hors terminal. */
    @Column(name = "workspace_id", updatable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24, updatable = false)
    private Kind kind;

    @Column(name = "client_name", nullable = false, length = 100, updatable = false)
    private String clientName;

    @Column(name = "recipient", nullable = false, length = 254, updatable = false)
    private String recipient;

    /** Faux quand le destinataire est le repli sur l'adresse du compte. */
    @Column(name = "recipient_verified", nullable = false, updatable = false)
    private boolean recipientVerified;

    @Column(name = "subject", nullable = false, length = 200, updatable = false)
    private String subject;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "attachment_count", nullable = false)
    private int attachmentCount;

    @Column(name = "body_text", columnDefinition = "text")
    private String bodyText;

    @Column(name = "body_html", columnDefinition = "text")
    private String bodyHtml;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ClientEmailStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column(name = "leased_until")
    private OffsetDateTime leasedUntil;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
