package fr.claudegateway.atelier.repoindex;

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
 * <b>L'index de repo persistant d'un projet</b>, côté gateway (F-148 / SF-148-07).
 *
 * <p>Une seule ligne par projet : la liste bornée des chemins de fichiers, relue <b>après</b> un tour
 * pour servir l'outil {@code glob} au tour suivant sans aller-retour runner. Aide de localisation :
 * il ne porte ni le contenu (donc {@code grep} reste sur le runner) ni de symboles.</p>
 *
 * <p><b>Isolation.</b> {@code (user_id, workspace_id)} est unique en base ; {@code host_id} est rangé
 * pour l'isolation et la purge.</p>
 */
@Entity
@Table(name = "repo_index_paths")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RepoIndexEntry {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /**
     * La liste des chemins relatifs, un par ligne (format de {@code safeTree}).
     *
     * <p>{@code columnDefinition = "text"} et <b>non</b> {@code @Lob} : sur PostgreSQL, {@code @Lob}
     * ferait attendre un {@code oid} à Hibernate là où la migration crée un {@code text} — la
     * validation de schéma refuserait de démarrer. Convention du projet.</p>
     */
    @Column(name = "paths", columnDefinition = "text")
    private String paths;

    /** Nombre de chemins indexés. */
    @Column(name = "path_count", nullable = false)
    private int pathCount;

    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;
}
