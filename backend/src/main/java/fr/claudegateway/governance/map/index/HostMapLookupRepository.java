package fr.claudegateway.governance.map.index;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Le journal des consultations de la carte (F-174 / SF-174-01).
 *
 * <p><b>Aucune lecture sans {@code user_id}.</b></p>
 */
@Repository
public interface HostMapLookupRepository extends JpaRepository<HostMapLookup, UUID> {

    /** Les consultations d'un compte sur une période, de la plus ancienne à la plus récente. */
    List<HostMapLookup> findByUserIdAndCreatedAtBetweenOrderByCreatedAtAsc(UUID userId,
            OffsetDateTime from, OffsetDateTime to);

    /** Purge à la suppression du compte. */
    @Modifying
    @Transactional
    @Query("DELETE FROM HostMapLookup l WHERE l.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    /** Purge à la suppression d'un poste. */
    @Modifying
    @Transactional
    @Query("DELETE FROM HostMapLookup l WHERE l.userId = :userId AND l.hostId = :hostId")
    int purgeHost(@Param("userId") UUID userId, @Param("hostId") UUID hostId);
}
