package fr.claudegateway.governance;

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
 * Un paquet <b>actif sur un projet</b> (F-51 / SF-51-02).
 *
 * <p>C'est l'unité qui fait agir la gouvernance : tant qu'une activation existe, les règles du paquet
 * rejoignent la consigne système du projet et ses contrôles se branchent sur les crochets de F-50
 * (SF-51-04).</p>
 *
 * <p>L'activation vit sur le <b>projet</b>, pas sur le poste (décision D1 du cadrage) : c'est le
 * projet qui porte des fichiers, et c'est par les projets qu'on range sous un poste neuf que celui-ci
 * embarque la sélection par défaut.</p>
 *
 * <p>{@link #appliedVersion} fige la version appliquée. Elle permet de dire « ce projet applique la
 * v2, le paquet est en v3 » sans imposer à personne une mise à jour automatique que personne n'a
 * demandée (décision D5).</p>
 *
 * <p><b>Isolation.</b> {@link #userId} <i>et</i> {@link #workspaceId} : l'unicité est
 * {@code (user_id, workspace_id, package_id)} et le projet est en outre vérifié comme possédé avant
 * toute écriture.</p>
 */
@Entity
@Table(name = "governance_activations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GovernanceActivation {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Projet sur lequel le paquet est actif (= {@code workspaces.id}). */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /** Paquet appliqué (= {@code governance_packages.id}). */
    @Column(name = "package_id", nullable = false, updatable = false)
    private UUID packageId;

    /** Version du paquet appliquée sur ce projet, figée à l'activation. */
    @Column(name = "applied_version", nullable = false)
    private int appliedVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GovernanceActivationStatus status;

    /** Instant du dernier dépôt abouti ; {@code null} tant que rien n'a pu être écrit. */
    @Column(name = "applied_at")
    private OffsetDateTime appliedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
