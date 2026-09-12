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
 * Un paquet <b>actif sur un poste</b> (F-51 / SF-51-02, <b>regrainé par F-75 / SF-75-01</b>).
 *
 * <p>C'est l'unité qui fait agir la gouvernance : tant qu'une activation existe, les règles du paquet
 * rejoignent la consigne système de <b>tous les projets du poste</b> et ses contrôles se branchent
 * sur les crochets de F-50 (SF-51-04).</p>
 *
 * <p><b>Le grain a changé.</b> F-51 posait l'activation sur le <i>projet</i> (décision D1 de son
 * cadrage). C'était une erreur de grain : la gouvernance se pose <b>une fois au niveau de la
 * machine</b>, et ce sont les <i>artefacts</i> qui sont par sujet — le {@code STATE.md} de chaque
 * dossier, la dette de promotion. Depuis F-75, on active une fois sur un poste et tout dossier
 * ajouté demain sous sa racine en hérite, sans que personne n'y pense. <b>Aucune dérogation par
 * dossier</b> (tranché par le PO) : une gouvernance qui se contourne au cas par cas cesse d'en être
 * une.</p>
 *
 * <p>{@link #appliedVersion} fige la version appliquée. Elle permet de dire « ce poste applique la
 * v2, le paquet est en v3 » sans imposer à personne une mise à jour automatique que personne n'a
 * demandée (décision D5).</p>
 *
 * <p><b>Isolation.</b> {@link #userId} <i>et</i> {@link #hostId} : l'unicité est
 * {@code (user_id, host_id, package_id)} et le poste est en outre vérifié comme possédé avant toute
 * écriture.</p>
 */
@Entity
@Table(name = "governance_host_activations")
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

    /**
     * Poste sur lequel le paquet est actif (= {@code runner_hosts.id}), ou la clé réservée du poste
     * virtuel « Hébergé » ({@link GovernanceHostRef#HOSTED_ID}) pour les projets sans machine.
     *
     * <p>Cette clé réservée ne sort jamais par l'API : F-71 a tranché que le poste « Hébergé » n'a
     * pas d'identifiant public. Elle n'existe ici que pour donner une clé d'unicité — une colonne
     * nulle ne dédoublonnerait rien sous PostgreSQL.</p>
     */
    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** Paquet appliqué (= {@code governance_packages.id}). */
    @Column(name = "package_id", nullable = false, updatable = false)
    private UUID packageId;

    /** Version du paquet appliquée sur ce poste, figée à l'activation. */
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
