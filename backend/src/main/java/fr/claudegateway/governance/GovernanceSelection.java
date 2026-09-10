package fr.claudegateway.governance;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
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
 * Un paquet <b>retenu</b> par un utilisateur (F-51 / SF-51-02) : son catalogue personnel.
 *
 * <p>C'est le second étage du catalogue — l'admin publie, chacun compose. Retenir un paquet ne
 * l'active nulle part : c'est un geste de <b>bibliothèque</b>, pas d'application. L'activation, elle,
 * se fait projet par projet ({@link GovernanceActivation}).</p>
 *
 * <p>{@link #defaultApplied} est la seule chose qui agit toute seule : un paquet ainsi marqué est
 * embarqué par les <b>projets à venir</b> de cet utilisateur, sans qu'il ait rien à cocher. Il ne
 * touche jamais les projets de quelqu'un d'autre — rien n'est partagé entre comptes (F-17, V3, hors
 * périmètre).</p>
 *
 * <p><b>Isolation.</b> {@link #userId} est la racine : l'unicité est {@code (user_id, package_id)} et
 * aucune méthode de lecture n'existe sans lui.</p>
 */
@Entity
@Table(name = "governance_selections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GovernanceSelection {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Paquet retenu (= {@code governance_packages.id}). */
    @Column(name = "package_id", nullable = false, updatable = false)
    private UUID packageId;

    /** Embarqué par les projets à venir de cet utilisateur, sans qu'il ait rien à cocher. */
    @Column(name = "default_applied", nullable = false)
    private boolean defaultApplied;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
