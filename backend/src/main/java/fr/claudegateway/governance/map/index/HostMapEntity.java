package fr.claudegateway.governance.map.index;

import java.time.LocalDate;
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
 * <b>Une ressource nommée par la carte</b> : compte AWS, cluster, dépôt, domaine… (F-174 / SF-174-02).
 *
 * <p>Deux origines (D2) : {@code MOTIF} — identifiant exact reconnu par motif, sûr par construction ;
 * {@code MODELE} — entité lue par Claude via {@code AIProvider}, dont les identifiants sont gardés
 * seulement s'ils figurent mot pour mot dans la section.</p>
 */
@Entity
@Table(name = "host_map_entities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostMapEntity {

    public static final String MOTIF = "MOTIF";
    public static final String MODELE = "MODELE";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "section_id", nullable = false, updatable = false)
    private UUID sectionId;

    @Column(name = "path", nullable = false, length = 500, updatable = false)
    private String path;

    @Column(name = "heading", length = 500)
    private String heading;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "label", nullable = false, length = 300)
    private String label;

    @Column(name = "label_norm", nullable = false, length = 300)
    private String labelNorm;

    @Column(name = "identifiers", columnDefinition = "text")
    private String identifiers;

    @Column(name = "attributes", columnDefinition = "text")
    private String attributes;

    @Column(name = "state", length = 32)
    private String state;

    @Column(name = "observed_on")
    private LocalDate observedOn;

    @Column(name = "origin", nullable = false, length = 8)
    private String origin;
}
