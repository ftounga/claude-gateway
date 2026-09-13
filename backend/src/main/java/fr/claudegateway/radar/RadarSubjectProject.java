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
 * <b>Le lien entre un sujet de la Vigie et un projet de la Forge</b> (F-106 / SF-106-06), sur un même poste.
 *
 * <p>Une ligne par paire, pour toujours : une paire refusée reste là, et c'est ce qui empêche l'analyse de
 * la reproposer.</p>
 */
@Entity
@Table(name = "radar_subject_projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarSubjectProject {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 16)
    private RadarSubjectProjectOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private RadarSubjectProjectState state;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
