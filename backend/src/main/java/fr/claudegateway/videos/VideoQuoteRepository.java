package fr.claudegateway.videos;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/** Les devis de vidéo (F-216). <b>Toute</b> lecture filtre sur {@code user_id}. */
public interface VideoQuoteRepository extends JpaRepository<VideoQuote, UUID> {

    /** Les devis d'un sujet du compte dans un statut, le plus récent d'abord. */
    List<VideoQuote> findByUserIdAndWorkspaceIdAndStatusOrderByCreatedAtDesc(UUID userId, UUID workspaceId,
            VideoQuote.Status status);

    Optional<VideoQuote> findByIdAndUserId(UUID id, UUID userId);

    /** Les devis ouverts d'un sujet du compte, verrouillés (imputations concurrentes), le plus récent d'abord. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from VideoQuote q where q.userId = :userId and q.workspaceId = :workspaceId"
            + " and q.status = fr.claudegateway.videos.VideoQuote.Status.OPEN order by q.createdAt desc")
    List<VideoQuote> lockOpen(@Param("userId") UUID userId, @Param("workspaceId") UUID workspaceId);

    /** Un devis du compte, verrouillé. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from VideoQuote q where q.id = :id and q.userId = :userId")
    Optional<VideoQuote> lockOne(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Ce que le compte a dépensé en vidéos depuis {@code since} (devis créés depuis), et combien de devis. */
    @Query("select coalesce(sum(q.spentUsd), 0) from VideoQuote q where q.userId = :userId and q.createdAt >= :since")
    BigDecimal spentSince(@Param("userId") UUID userId, @Param("since") OffsetDateTime since);

    long countByUserIdAndCreatedAtGreaterThanEqual(UUID userId, OffsetDateTime since);
}
