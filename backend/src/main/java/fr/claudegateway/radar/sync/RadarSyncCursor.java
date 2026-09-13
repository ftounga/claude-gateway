package fr.claudegateway.radar.sync;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.UpdateTimestamp;
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
 * <b>Où la collecte en est, pour un fil</b> (F-100 / SF-100-03) : l'instant du message le plus récent déjà
 * accepté par la file d'analyse. Il n'avance qu'après acceptation, et ne recule jamais.
 */
@Entity
@Table(name = "radar_sync_cursors")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarSyncCursor {

    public static final String SOURCE_TEAMS = "TEAMS";
    public static final String SOURCE_DEPOT = "DEPOT";
    public static final int MAX_REF_CHARS = 512;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** {@code TEAMS} ou {@code DEPOT}. */
    @Column(name = "source", nullable = false, length = 16, updatable = false)
    private String source;

    @Column(name = "conversation_ref", nullable = false, length = MAX_REF_CHARS, updatable = false)
    private String conversationRef;

    /** {@code CONVERSATION}, {@code CHANNEL}, {@code MEETING}, {@code RECORDING}. */
    @Column(name = "kind", length = 16)
    private String kind;

    @Column(name = "cursor_at", nullable = false)
    private OffsetDateTime cursorAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
