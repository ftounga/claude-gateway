package fr.claudegateway.radar.analysis;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Baux d'analyse (F-101). Toute méthode porte {@code user_id} et {@code host_id}, sauf la purge d'un compte. */
public interface RadarAnalysisLeaseRepository extends JpaRepository<RadarAnalysisLease, UUID> {

    /** Prend le bail s'il est libre, échu, ou déjà à soi. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RadarAnalysisLease l set l.owner = :owner, l.leasedUntil = :until"
            + " where l.userId = :userId and l.hostId = :hostId"
            + " and (l.leasedUntil < :now or l.owner = :owner)")
    int acquire(@Param("userId") UUID userId, @Param("hostId") UUID hostId, @Param("owner") String owner,
            @Param("now") OffsetDateTime now, @Param("until") OffsetDateTime until);

    /** Rend le bail, s'il est encore à soi. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RadarAnalysisLease l set l.leasedUntil = :now"
            + " where l.userId = :userId and l.hostId = :hostId and l.owner = :owner")
    int release(@Param("userId") UUID userId, @Param("hostId") UUID hostId, @Param("owner") String owner,
            @Param("now") OffsetDateTime now);

    boolean existsByUserIdAndHostId(UUID userId, UUID hostId);

    @Modifying
    @Query("delete from RadarAnalysisLease l where l.userId = :userId and l.hostId = :hostId")
    int purgeScope(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    @Modifying
    @Query("delete from RadarAnalysisLease l where l.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
