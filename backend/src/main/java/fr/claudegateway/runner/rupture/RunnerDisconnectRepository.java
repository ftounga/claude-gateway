package fr.claudegateway.runner.rupture;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Les ruptures consignées (F-161 / SF-161-03). Toute lecture est filtrée par {@code user_id}. */
public interface RunnerDisconnectRepository extends JpaRepository<RunnerDisconnect, UUID> {

    /**
     * Les ruptures de CE compte sur une période, les plus récentes d'abord.
     *
     * <p>Le filtre {@code user_id} n'est pas une commodité : sans lui, l'écran d'un administrateur
     * rendrait les postes d'autres clients.</p>
     */
    List<RunnerDisconnect> findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            UUID userId, OffsetDateTime from, OffsetDateTime to);

    /** Combien de ruptures par cause, sur la période — la première question qu'on se pose. */
    @Query("""
            select d.cause as cause, count(d) as total
            from RunnerDisconnect d
            where d.userId = :userId and d.createdAt between :from and :to
            group by d.cause
            """)
    List<CauseCount> countByCause(@Param("userId") UUID userId,
                                  @Param("from") OffsetDateTime from,
                                  @Param("to") OffsetDateTime to);

    /** Projection d'un décompte par cause. */
    interface CauseCount {
        RunnerDisconnectCause getCause();

        long getTotal();
    }
}
