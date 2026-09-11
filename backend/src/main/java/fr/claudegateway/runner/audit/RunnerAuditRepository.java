package fr.claudegateway.runner.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistance du journal d'audit du runner (F-38 / SF-38-08). Toute lecture filtre sur
 * {@code user_id} <b>et</b> {@code workspace_id} : le journal dit ce qui s'est passé sur la machine
 * de son propriétaire, jamais sur celle d'un autre.
 */
@Repository
public interface RunnerAuditRepository extends JpaRepository<RunnerAudit, UUID> {

    /** Dernières lignes du workspace possédé, du plus récent au plus ancien. */
    List<RunnerAudit> findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(UUID userId, UUID workspaceId,
            Pageable pageable);

    /**
     * Dernière ligne de journal d'un projet possédé — l'outil qui a tourné en dernier (F-49 /
     * SF-49-01). Appelée seulement pour les projets ayant une activité dans la fenêtre observée :
     * un projet muet ne coûte aucune requête.
     */
    Optional<RunnerAudit> findFirstByUserIdAndWorkspaceIdOrderByCreatedAtDesc(UUID userId,
            UUID workspaceId);

    /**
     * Activité des projets d'un <b>poste</b>, agrégée par projet sur une fenêtre (F-49 / SF-49-01).
     *
     * <p>Une seule requête pour tout le poste, au lieu d'un journal lu projet par projet. Le filtre
     * porte sur {@code user_id} <b>et</b> {@code host_id} : l'index
     * {@code idx_runner_audit_user_host_created} (migration 064) le couvre exactement.</p>
     *
     * <p>Les lignes sans projet — le coupe-circuit, qui coupe la machine et non un dossier — sont
     * écartées : elles ne décrivent l'activité d'aucun projet.</p>
     */
    @Query("""
            select a.workspaceId as workspaceId, max(a.createdAt) as lastAt, count(a) as calls
            from RunnerAudit a
            where a.userId = :userId and a.hostId = :hostId and a.workspaceId is not null
              and a.createdAt >= :since
            group by a.workspaceId
            """)
    List<RunnerAuditActivity> aggregateActivityByHost(@Param("userId") UUID userId,
            @Param("hostId") UUID hostId, @Param("since") OffsetDateTime since);

    /**
     * Purge à la suppression du compte (SF-38-14) : le journal porte des données personnelles
     * (chemins lus, commandes exécutées) et ne survit pas au compte qu'il décrit.
     */
    void deleteByUserId(UUID userId);

    /**
     * Purge à la suppression d'un <b>projet</b> (F-69 / SF-69-01), pour la même raison à une échelle
     * plus fine : sans elle, le journal d'un projet supprimé survivait <b>sans porte d'entrée</b> —
     * sa seule lecture est {@code GET /workspaces/{id}/runner/audit}, sur un projet qui n'existe
     * plus — tout en continuant de porter des commandes exécutées et des chemins lus.
     *
     * <p>Le filtre porte sur {@code user_id} <b>et</b> {@code workspace_id} : un identifiant de
     * projet ne suffit jamais à effacer le journal de quelqu'un d'autre.</p>
     */
    void deleteByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);
}
