package fr.claudegateway.radar;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
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
 * Un <b>sujet</b> du Radar (F-99 / SF-99-01) : l'unité qui traverse les sources — « le MFA », « la
 * migration LDAP ».
 *
 * <p>Les valeurs portées ici (état, prochaine étape, échéance) sont les valeurs <b>courantes</b> ;
 * leurs preuves vivent dans {@code radar_evidence_links}. Le registre refuse d'écrire l'une sans
 * l'autre.</p>
 *
 * <p><b>Isolation.</b> {@link #userId} et {@link #hostId} ; aucune lecture n'existe sans les deux.</p>
 */
@Entity
@Table(name = "radar_subjects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarSubject {

    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_NEXT_STEP_LENGTH = 500;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private RadarSubjectState state;

    @Column(name = "next_step", length = MAX_NEXT_STEP_LENGTH)
    private String nextStep;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /** Instant de la preuve la plus récente de la chronologie. */
    @Column(name = "last_activity_at")
    private OffsetDateTime lastActivityAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
