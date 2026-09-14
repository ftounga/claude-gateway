package fr.claudegateway.governance.control;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

/**
 * Persistance des promotions reportées faute de poste (F-93 / SF-93-05). Aucune logique ici : la
 * fusion, la durée de vie et la réclamation-une-fois vivent dans {@link JpaPromotionReporteeStore}.
 */
@Repository
public interface PromotionReporteeRepository extends JpaRepository<PromotionReporteeEntity, UUID> {

    /** La ligne d'un triple, si elle existe (lecture simple, pour fusionner ou constater). */
    Optional<PromotionReporteeEntity> findByUserIdAndHostIdAndWorkspaceId(
            UUID userId, UUID hostId, UUID workspaceId);

    /**
     * La ligne d'un triple <b>sous verrou d'écriture</b> : la réclamation lit puis supprime dans la
     * même transaction, et deux pods concurrents ne réclament jamais deux fois (D4). {@code SELECT …
     * FOR UPDATE}, supporté par H2 et PostgreSQL.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PromotionReporteeEntity p where p.userId = :userId "
            + "and p.hostId = :hostId and p.workspaceId = :workspaceId")
    Optional<PromotionReporteeEntity> lockByTriple(@Param("userId") UUID userId,
            @Param("hostId") UUID hostId, @Param("workspaceId") UUID workspaceId);

    boolean existsByUserIdAndHostIdAndWorkspaceId(UUID userId, UUID hostId, UUID workspaceId);

    /** Purge les reports dont la date est antérieure à la borne de durée de vie. */
    long deleteByReportedAtBefore(OffsetDateTime cutoff);

    /** Les identifiants des entrées les plus anciennes, pour ramener le registre sous sa borne. */
    @Query("select p.id from PromotionReporteeEntity p order by p.reportedAt asc")
    List<UUID> findOldestIds(Pageable pageable);
}
