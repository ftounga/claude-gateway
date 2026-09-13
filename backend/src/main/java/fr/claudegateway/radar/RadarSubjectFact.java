package fr.claudegateway.radar;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
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
 * Une <b>phrase du résumé</b> d'un sujet (F-99 / SF-99-01). Le résumé est rendu phrase par phrase,
 * chacune avec ses renvois (cadrage §4.1) : une phrase sans lien {@code SUMMARY} n'existe pas.
 */
@Entity
@Table(name = "radar_subject_facts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarSubjectFact {

    public static final int MAX_TEXT_LENGTH = 500;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    /** Rang de la phrase dans le résumé, à partir de 0. */
    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "text", nullable = false, length = MAX_TEXT_LENGTH)
    private String text;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
