package fr.claudegateway.atelier.veille;

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
 * <b>Le dernier instantané d'une veille</b> (F-230 / SF-230-02, D3) : la liste normalisée du dernier passage qui a
 * changé quelque chose. Un par veille ; le passage suivant s'y compare.
 */
@Entity
@Table(name = "jarvis_watch_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisWatchSnapshot {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "watch_id", nullable = false, updatable = false)
    private UUID watchId;

    @Column(name = "items_json", nullable = false, columnDefinition = "text")
    private String itemsJson;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "taken_at", nullable = false)
    private OffsetDateTime takenAt;
}
