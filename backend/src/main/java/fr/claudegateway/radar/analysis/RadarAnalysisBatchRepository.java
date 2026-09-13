package fr.claudegateway.radar.analysis;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import fr.claudegateway.radar.RadarScope;

/**
 * Lots de la file d'analyse (F-101). Toute lecture porte {@code user_id} et {@code host_id} ; seuls le
 * repérage des postes à traiter et l'expiration, qui sont des balayages de fond sans contenu rendu,
 * parcourent la table entière.
 */
public interface RadarAnalysisBatchRepository extends JpaRepository<RadarAnalysisBatch, UUID> {

    Optional<RadarAnalysisBatch> findByUserIdAndHostIdAndBatchKey(UUID userId, UUID hostId, String batchKey);

    Optional<RadarAnalysisBatch> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);

    /** Les postes qui ont au moins un lot prenable. */
    @Query("select distinct new fr.claudegateway.radar.RadarScope(b.userId, b.hostId) from RadarAnalysisBatch b"
            + " where b.expiresAt > :now and ("
            + " (b.status in (fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus.PENDING,"
            + " fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus.DEFERRED)"
            + " and (b.nextAttemptAt is null or b.nextAttemptAt <= :now))"
            + " or (b.status = fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus.PROCESSING"
            + " and b.claimedAt < :staleBefore))")
    List<RadarScope> findClaimableScopes(@Param("now") OffsetDateTime now,
            @Param("staleBefore") OffsetDateTime staleBefore, Pageable page);

    /** Les lots prenables d'un poste, dans l'ordre de réception. */
    @Query("select b.id from RadarAnalysisBatch b where b.userId = :userId and b.hostId = :hostId"
            + " and b.expiresAt > :now and ("
            + " (b.status in (fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus.PENDING,"
            + " fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus.DEFERRED)"
            + " and (b.nextAttemptAt is null or b.nextAttemptAt <= :now))"
            + " or (b.status = fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus.PROCESSING"
            + " and b.claimedAt < :staleBefore))"
            + " order by b.receivedAt asc, b.id asc")
    List<UUID> findClaimableIds(@Param("userId") UUID userId, @Param("hostId") UUID hostId,
            @Param("now") OffsetDateTime now, @Param("staleBefore") OffsetDateTime staleBefore, Pageable page);

    /** Prend un lot, s'il est encore prenable. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RadarAnalysisBatch b set b.status = fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus.PROCESSING,"
            + " b.claimedAt = :now, b.attempts = b.attempts + 1, b.updatedAt = :now"
            + " where b.id = :id and b.userId = :userId and b.hostId = :hostId and b.expiresAt > :now and ("
            + " (b.status in (fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus.PENDING,"
            + " fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus.DEFERRED)"
            + " and (b.nextAttemptAt is null or b.nextAttemptAt <= :now))"
            + " or (b.status = fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus.PROCESSING"
            + " and b.claimedAt < :staleBefore))")
    int claim(@Param("id") UUID id, @Param("userId") UUID userId, @Param("hostId") UUID hostId,
            @Param("now") OffsetDateTime now, @Param("staleBefore") OffsetDateTime staleBefore);

    /** Expiration : le brut d'un lot jamais analysé est effacé, le lot passe {@code EXPIRED}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RadarAnalysisBatch b set b.payload = null, b.rawDeletedAt = :now, b.updatedAt = :now,"
            + " b.status = fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus.EXPIRED"
            + " where b.expiresAt <= :now"
            + " and b.status <> fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus.DONE"
            + " and b.status <> fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus.EXPIRED")
    int expireUnanalyzed(@Param("now") OffsetDateTime now);

    /** Expiration : filet pour un lot analysé qui aurait gardé son brut. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RadarAnalysisBatch b set b.payload = null, b.rawDeletedAt = :now, b.updatedAt = :now"
            + " where b.expiresAt <= :now and b.payload is not null")
    int expireRemainingRaw(@Param("now") OffsetDateTime now);

    /** Les lots des synchros du poste, comptés par synchro et par statut — jamais le texte. */
    @Query("select new fr.claudegateway.radar.analysis.RadarAnalysisSyncCount(b.syncId, b.status, count(b),"
            + " sum(b.exchangesCount), sum(b.messagesCount), sum(b.retainedCount),"
            + " sum(b.subjectsAttached), sum(b.subjectsCreated),"
            + " sum(b.triageInputTokens), sum(b.triageOutputTokens), sum(b.extractionInputTokens),"
            + " sum(b.extractionOutputTokens), sum(b.cacheReadTokens), sum(b.cacheWriteTokens))"
            + " from RadarAnalysisBatch b where b.userId = :userId and b.hostId = :hostId"
            + " and b.syncId in :syncIds group by b.syncId, b.status")
    List<RadarAnalysisSyncCount> countBySync(@Param("userId") UUID userId, @Param("hostId") UUID hostId,
            @Param("syncIds") Collection<UUID> syncIds);

    /** Lots reportés pour un motif, comptés par synchro (SF-101-05 : l'arrêt sur réserve). */
    @Query("select b.syncId, count(b) from RadarAnalysisBatch b where b.userId = :userId and b.hostId = :hostId"
            + " and b.syncId in :syncIds and b.status = fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus.DEFERRED"
            + " and b.failureCode = :code group by b.syncId")
    List<Object[]> countDeferredBySync(@Param("userId") UUID userId, @Param("hostId") UUID hostId,
            @Param("syncIds") Collection<UUID> syncIds, @Param("code") String code);

    @Modifying
    @Query("delete from RadarAnalysisBatch b where b.userId = :userId and b.hostId = :hostId")
    int purgeScope(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    @Modifying
    @Query("delete from RadarAnalysisBatch b where b.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
