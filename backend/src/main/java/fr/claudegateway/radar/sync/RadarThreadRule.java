package fr.claudegateway.radar.sync;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
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
 * <b>Ce que l'utilisateur a dit d'un fil</b> (F-100) : <i>ignorer ce fil</i>, <i>lire ce canal</i>.
 * Correction souveraine (cadrage §4.2) : jamais remise en cause par une synchro, annulable.
 */
@Entity
@Table(name = "radar_thread_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarThreadRule {

    /** Les deux règles. */
    public enum Rule {
        /** Ignorer ce fil : la collecte l'écarte. */
        IGNORE,
        /** Lire ce canal en entier, et non plus seulement les fils de l'utilisateur. */
        READ_CHANNEL
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

    @Column(name = "conversation_ref", nullable = false, length = RadarSyncCursor.MAX_REF_CHARS, updatable = false)
    private String conversationRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule", nullable = false, length = 16, updatable = false)
    private Rule rule;

    /** Le nom du fil tel que la couverture le montrait, pour qu'on sache ce qu'on a ignoré. */
    @Column(name = "label", length = 200)
    private String label;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
