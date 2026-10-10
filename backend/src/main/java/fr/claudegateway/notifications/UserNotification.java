package fr.claudegateway.notifications;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Une ligne du <b>centre de notifications</b> (F-185 / SF-185-04) : une chose qui a attendu
 * l'utilisateur. Scellée par {@code user_id} ; le {@code subject} (nom du terminal) ne sert qu'à
 * l'affichage dans l'application authentifiée — jamais dans une charge push.
 */
@Entity
@Table(name = "user_notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserNotification {

    public static final int MAX_SUBJECT_LENGTH = 200;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Racine de l'isolation : aucune lecture sans ce filtre. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Terminal concerné, {@code null} pour un événement hors terminal. */
    @Column(name = "workspace_id", updatable = false)
    private UUID workspaceId;

    /** Code de {@link fr.claudegateway.push.PushEvent}. */
    @Column(name = "event", nullable = false, length = 40, updatable = false)
    private String event;

    /** Nom du terminal au moment de l'événement, copié : il survit à un renommage. */
    @Column(name = "subject", length = MAX_SUBJECT_LENGTH, updatable = false)
    private String subject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** {@code null} : non lue. */
    @Column(name = "read_at")
    private OffsetDateTime readAt;
}
