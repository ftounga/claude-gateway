package fr.claudegateway.jarvis.state;

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
 * <b>Un fait d'état du poste</b> (F-247 / SF-247-02, cadrage §4.1) : une ligne par client + objet + attribut, mise à
 * jour en place (porte Z11). La valeur est <b>scellée</b> (D4) ; la clé technique reste en clair pour la recherche
 * indexée. {@code observedAt} est l'heure de la sortie d'outil, jamais l'heure d'écriture.
 */
@Entity
@Table(name = "jarvis_state_facts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisStateFact {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Le client (un {@code RunnerHost}). */
    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** Le sujet où le fait a été vu (facultatif). */
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "object_kind", nullable = false, length = 24)
    private String objectKind;

    @Column(name = "object_key", nullable = false, length = 300, updatable = false)
    private String objectKey;

    @Column(name = "attribute", nullable = false, length = 32, updatable = false)
    private String attribute;

    @Column(name = "value_sealed", nullable = false, length = 4000)
    private String valueSealed;

    @Column(name = "previous_value_sealed", length = 4000)
    private String previousValueSealed;

    @Column(name = "previous_observed_at")
    private OffsetDateTime previousObservedAt;

    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;

    @Column(name = "source", nullable = false, length = 8)
    private String source;

    @Column(name = "source_ref", length = 64)
    private String sourceRef;

    @Column(name = "confidence", nullable = false, length = 8)
    private String confidence;

    /** L'heure (d'observation) du dernier changement de valeur ; {@code null} tant que la valeur n'a pas changé. */
    @Column(name = "changed_at")
    private OffsetDateTime changedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
