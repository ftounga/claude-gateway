package fr.claudegateway.jarvis.companion;

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
 * Ce que le téléphone dit à voix haute (F-232 / SF-232-03, D1b) : la fin d'un tour né à la voix, ou une alerte de
 * veille. {@link #link} : le chemin de l'application que la réponse ouvre.
 */
@Entity
@Table(name = "jarvis_companion_announcements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisCompanionAnnouncement {

    public static final String KIND_TURN = "TURN";
    public static final String KIND_ALERT = "ALERT";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    private String kind;

    @Column(name = "text", nullable = false, length = 600, updatable = false)
    private String text;

    @Column(name = "link", nullable = false, length = 300, updatable = false)
    private String link;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
