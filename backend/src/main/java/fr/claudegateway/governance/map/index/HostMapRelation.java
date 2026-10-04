package fr.claudegateway.governance.map.index;

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
 * <b>Un lien entre deux ressources de la carte</b> (F-174 / SF-174-02) : « le cluster X tourne dans
 * le compte Y », « l'accès Z est accordé par l'équipe W ». Par libellé, pas par identifiant de ligne :
 * une entité ré-extraite change d'identifiant, son nom reste.
 */
@Entity
@Table(name = "host_map_relations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostMapRelation {

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

    @Column(name = "from_label", nullable = false, length = 300)
    private String fromLabel;

    @Column(name = "to_label", nullable = false, length = 300)
    private String toLabel;

    @Column(name = "nature", nullable = false, length = 100)
    private String nature;
}
