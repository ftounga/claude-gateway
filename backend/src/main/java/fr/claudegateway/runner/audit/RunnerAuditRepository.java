package fr.claudegateway.runner.audit;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * Purge à la suppression du compte (SF-38-14) : le journal porte des données personnelles
     * (chemins lus, commandes exécutées) et ne survit pas au compte qu'il décrit.
     */
    void deleteByUserId(UUID userId);
}
