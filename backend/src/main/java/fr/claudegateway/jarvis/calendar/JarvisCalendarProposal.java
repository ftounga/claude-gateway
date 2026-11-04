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
 * <b>Un rendez-vous repéré dans un courrier</b> (F-243 / SF-243-04, D6, D7, D8) — une invitation {@code .ics} (analysée
 * sans IA) ou une proposition écrite (repérée au tri). Le titre, le lieu et l'expéditeur sont <b>scellés</b>
 * ({@link #sealed}). Une proposition sans réponse <b>expire</b> au début du rendez-vous. Elle ne pose jamais rien : la pose
 * passe par {@code agenda_poser} et le « oui » vérifié.
 */
@Entity
@Table(name = "jarvis_calendar_proposals")
@Getter
@Setter
@NoArgsConstructor
public class JarvisCalendarProposal {

    public enum Kind {
        /** Une invitation {@code .ics} ({@code METHOD:REQUEST}). */
        INVITATION,
        /** Une invitation annulée ({@code METHOD:CANCEL}). */
        CANCELLATION,
        /** Une date écrite, certitude haute, créneau résolu. */
        PROPOSAL,
        /** Une envie de se voir sans créneau précis : Jarvis demande le créneau. */
        ASK
    }

    public enum Status {
        OPEN, SAID, DONE, DISMISSED, EXPIRED
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

    @Column(name = "mail_item_id", nullable = false, updatable = false)
    private UUID mailItemId;

    /** Vrai si la boîte est une boîte gmail.com (agenda perso : lecture seule, D4). */
    @Column(name = "personal_box", nullable = false)
    private boolean personalBox;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @Column(name = "ical_uid", length = 512)
    private String icalUid;

    @Column(name = "starts_at")
    private OffsetDateTime startsAt;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Column(name = "all_day", nullable = false)
    private boolean allDay;

    /** Déjà présent dans un agenda lu (même {@code UID}) : on ne propose pas de le poser (D6). */
    @Column(name = "in_calendar", nullable = false)
    private boolean inCalendar;

    /** JSON {titre, lieu, expéditeur} scellé. */
    @Column(name = "sealed", nullable = false, length = 4000)
    private String sealed;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.OPEN;

    @Column(name = "briefed_at")
    private OffsetDateTime briefedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
}
