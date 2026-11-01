package fr.claudegateway.atelier.veille;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Les alertes des veilles — toujours sous {@code user_id} (F-230 / SF-230-03). */
public interface JarvisWatchAlertRepository extends JpaRepository<JarvisWatchAlert, UUID> {

    Optional<JarvisWatchAlert> findByIdAndUserId(UUID id, UUID userId);

    List<JarvisWatchAlert> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable page);

    /** Ce qu'une veille a dépensé depuis le début du mois (plafond mensuel, SF-230-03). */
    @Query("select coalesce(sum(a.costUsd), 0) from JarvisWatchAlert a where a.watchId = :watchId "
            + "and a.userId = :userId and a.createdAt >= :since")
    BigDecimal spentSince(@Param("watchId") UUID watchId, @Param("userId") UUID userId,
            @Param("since") OffsetDateTime since);

    /** Les alertes d'une fenêtre, pour ce compte — la mesure d'adoption (SF-230-04). */
    List<JarvisWatchAlert> findByUserIdAndCreatedAtGreaterThanEqual(UUID userId, OffsetDateTime since);
}
