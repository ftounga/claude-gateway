package fr.claudegateway.jarvis.calendar;

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
 * <b>Une action d'agenda qui engage</b> (F-243 / SF-243-03, D5) — même modèle que {@code jarvis_mail_actions}
 * (F-242 / SF-242-06) : en attente d'un « oui » de l'utilisateur, vérifié par la passerelle sur son message persisté.
 * {@link #payload} scellé (les champs <b>exacts</b> qui seront posés) ; {@link #summary} = la reformulation dite.
 */
@Entity
@Table(name = "jarvis_calendar_actions")
@Getter
@Setter
@NoArgsConstructor
public class JarvisCalendarAction {

    /** {@code CREATE} (SF-243-03) ; {@code ACCEPT} / {@code DECLINE} (SF-243-04, réponse à une invitation). */
    public enum Kind {
        CREATE, ACCEPT, DECLINE
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

    @Column(name = "grant_id", nullable = false, updatable = false)
    private UUID grantId;

    /** Le fil Jarvis où l'action a été proposée : seul un message de CE fil peut la confirmer. */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    /** Le courrier d'origine (SF-243-04), ou {@code null}. */
    @Column(name = "mail_item_id")
    private UUID mailItemId;

    @Column(name = "payload", nullable = false, length = 16000)
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

    /** L'identifiant de l'événement créé chez Google (une fois exécutée). */
    @Column(name = "google_event_id", length = 1024)
    private String googleEventId;

    @Column(name = "error", length = 300)
    private String error;
}
