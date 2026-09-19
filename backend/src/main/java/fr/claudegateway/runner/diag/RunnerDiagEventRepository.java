package fr.claudegateway.runner.diag;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistance du journal de diagnostic du runner (F-132 / SF-132-02). Toute lecture filtre sur
 * {@code user_id} <b>et</b> {@code host_id} : le journal dit ce qui s'est passé sur le poste de son
 * propriétaire, jamais sur celui d'un autre.
 */
@Repository
public interface RunnerDiagEventRepository extends JpaRepository<RunnerDiagEventEntity, UUID> {

    /**
     * Derniers événements d'un poste possédé, filtrés par niveau (ensemble de niveaux autorisés) et
     * fenêtre temporelle, du plus récent au plus ancien. L'index
     * {@code idx_runner_diag_events_user_host_created} couvre le filtre + le tri.
     */
    @Query("""
            select e from RunnerDiagEventEntity e
            where e.userId = :userId and e.hostId = :hostId
              and e.level in :levels
              and e.createdAt >= :since and e.createdAt <= :until
            order by e.createdAt desc
            """)
    List<RunnerDiagEventEntity> findWindow(@Param("userId") UUID userId,
            @Param("hostId") UUID hostId, @Param("levels") List<String> levels,
            @Param("since") OffsetDateTime since, @Param("until") OffsetDateTime until,
            Pageable pageable);

    /** Nombre de lignes d'un poste, pour borner l'anneau (SF-132-02). */
    long countByUserIdAndHostId(UUID userId, UUID hostId);

    /** Les identifiants des lignes les plus anciennes d'un poste — pour tailler l'anneau. */
    @Query("""
            select e.id from RunnerDiagEventEntity e
            where e.userId = :userId and e.hostId = :hostId
            order by e.createdAt asc
            """)
    List<UUID> findOldestIds(@Param("userId") UUID userId, @Param("hostId") UUID hostId,
            Pageable pageable);

    /** Purge TTL (SF-132-02) : supprime toutes les lignes plus anciennes que la coupure. */
    @Modifying
    @Query("delete from RunnerDiagEventEntity e where e.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
