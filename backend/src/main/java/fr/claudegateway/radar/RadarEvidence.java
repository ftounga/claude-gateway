package fr.claudegateway.radar;

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
 * Une <b>preuve</b> (F-99 / SF-99-01) : un extrait, pas une archive (cadrage §4.6) — la source, son
 * identifiant stable, l'instant, une <b>citation courte</b> et le lien profond.
 *
 * <p><b>Idempotence.</b> Unique par {@code (user_id, host_id, source, source_ref)} : une synchro
 * reprise après une interruption ne crée jamais de doublon. Une preuve n'appartient à aucun sujet en
 * propre ; ce qu'elle justifie vit dans {@link RadarEvidenceLink}, ce qui permet de fusionner ou
 * séparer des sujets en déplaçant des liens, jamais des preuves.</p>
 */
@Entity
@Table(name = "radar_evidence")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarEvidence {

    public static final int MAX_SOURCE_REF_LENGTH = 512;
    public static final int MAX_QUOTE_LENGTH = 280;
    public static final int MAX_DEEP_LINK_LENGTH = 2048;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 24)
    private RadarEvidenceSource source;

    @Column(name = "source_ref", nullable = false, updatable = false, length = MAX_SOURCE_REF_LENGTH)
    private String sourceRef;

    /** Instant dans la source : date du message, seconde de réunion, date du courriel collé. */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "quote", nullable = false, updatable = false, length = MAX_QUOTE_LENGTH)
    private String quote;

    @Column(name = "deep_link", updatable = false, length = MAX_DEEP_LINK_LENGTH)
    private String deepLink;

    /** Auteur, s'il est connu de l'annuaire. */
    @Column(name = "author_person_id", updatable = false)
    private UUID authorPersonId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
