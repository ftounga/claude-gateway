package fr.claudegateway.jarvis.notices;

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
 * <b>Une annonce d'interface</b> (F-249 / SF-249-03, D10) — ce que l'application montre en pop-up sur tous les écrans et
 * que Jarvis dit dans {@code /jarvis} : jamais un message de conversation ni un tour. Une clé ({@code dedup_key}) par
 * compte : l'annonce d'un sujet <b>cumule</b> tant qu'elle n'est pas vue.
 */
@Entity
@Table(name = "jarvis_notices")
@Getter
@Setter
@NoArgsConstructor
public class JarvisNotice {

    /** Les genres d'annonce. */
    public static final String MAILS_ON_SUBJECT = "MAILS_ON_SUBJECT";
    public static final String MORNING_RECAP = "MORNING_RECAP";
    public static final String DEPOSIT_REMINDER = "DEPOSIT_REMINDER";
    public static final String MEETING_SOON = "MEETING_SOON";
    public static final String IGNORE_OFFER = "IGNORE_OFFER";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id")
    private UUID hostId;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "kind", nullable = false, length = 24)
    private String kind;

    @Column(name = "dedup_key", nullable = false, length = 120)
    private String dedupKey;

    @Column(name = "display_text", nullable = false, length = 300)
    private String displayText;

    @Column(name = "speech_text", nullable = false, length = 300)
    private String speechText;

    @Column(name = "link", length = 200)
    private String link;

    @Column(name = "item_count", nullable = false)
    private int itemCount = 1;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "seen_at")
    private OffsetDateTime seenAt;

    @Column(name = "spoken_at")
    private OffsetDateTime spokenAt;
}
