package fr.claudegateway.atelier.quickaction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Les actions rapides personnalisées (F-275) — toujours sous {@code user_id}. */
public interface QuickActionRepository extends JpaRepository<QuickAction, UUID> {

    /** Les actions visibles dans un terminal : COMPTE, POSTE de ce poste, SUJET de ce terminal. */
    @Query("select a from QuickAction a where a.userId = :userId and (a.scope = 'COMPTE'"
            + " or (a.scope = 'POSTE' and a.hostId = :hostId) or (a.scope = 'SUJET' and a.workspaceId = :workspaceId))"
            + " order by a.labelKey asc")
    List<QuickAction> findVisible(@Param("userId") UUID userId, @Param("hostId") UUID hostId,
            @Param("workspaceId") UUID workspaceId);

    Optional<QuickAction> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from QuickAction a where a.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
