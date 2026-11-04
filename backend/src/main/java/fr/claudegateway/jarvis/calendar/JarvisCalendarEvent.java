package fr.claudegateway.jarvis.calendar;

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
 * <b>Une occurrence d'agenda en cache</b> (F-243 / SF-243-01, D2, D13) — la fenêtre J-1 → J+60 seulement. Le titre, le
 * lieu et la description courte sont <b>scellés</b> ensemble dans {@link #sealed} (JSON de {@link EventText}, patron
 * {@code jarvis_mail_items}) ; seules les heures sont en clair, pour l'index ({@code user_id}, {@code starts_at}).
 *
 * <p>{@link #recurrenceId} : l'instant d'origine de l'occurrence (ISO-8601) pour un événement récurrent, {@code null}
 * pour un événement simple. ({@link #icalUid}, {@link #recurrenceId}) identifie une occurrence.</p>
 */
@Entity
@Table(name = "jarvis_calendar_events")
@Getter
@Setter
@NoArgsConstructor
public class JarvisCalendarEvent {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Racine d'isolation multi-tenant : tout accès filtre sur ce champ. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "feed_id", nullable = false, updatable = false)
    private UUID feedId;

    @Column(name = "ical_uid", nullable = false, length = 512)
    private String icalUid;

    @Column(name = "recurrence_id", length = 40)
    private String recurrenceId;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Column(name = "all_day", nullable = false)
    private boolean allDay;

    /** JSON de {@link EventText} scellé ({@code dataKey.iv.ciphertext}). */
    @Column(name = "sealed", nullable = false, length = 4000)
    private String sealed;

    /** {@code CONFIRMED} ou {@code TENTATIVE} (les occurrences annulées ne sont pas gardées). */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
