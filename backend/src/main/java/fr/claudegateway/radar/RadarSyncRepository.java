package fr.claudegateway.radar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Synchros (F-99). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarSyncRepository extends JpaRepository<RadarSync, UUID> {

    Optional<RadarSync> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);

    List<RadarSync> findByUserIdAndHostIdOrderByStartedAtDesc(UUID userId, UUID hostId, Pageable page);

    /** La dernière synchro du poste dans l'un de ces statuts (fenêtre de lecture, SF-100-02). */
    Optional<RadarSync> findFirstByUserIdAndHostIdAndStatusInOrderByStartedAtDesc(UUID userId, UUID hostId,
            java.util.Collection<RadarSyncStatus> statuses);

    /** Ajoute la consommation d'une analyse à sa synchro (F-101 / SF-101-01), sans relire la ligne. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RadarSync x set x.consumedTokens = x.consumedTokens + :tokens"
            + " where x.id = :id and x.userId = :userId and x.hostId = :hostId")
    int addConsumedTokens(@Param("id") UUID id, @Param("userId") UUID userId, @Param("hostId") UUID hostId,
            @Param("tokens") long tokens);

    /** Consommation des synchros du poste commencées depuis un instant (F-101 / SF-101-05 : la réserve). */
    @Query("select coalesce(sum(x.consumedTokens), 0) from RadarSync x"
            + " where x.userId = :userId and x.hostId = :hostId and x.startedAt >= :from"
            + " and x.reserveExempt = false")
    long sumConsumedSince(@Param("userId") UUID userId, @Param("hostId") UUID hostId,
            @Param("from") java.time.OffsetDateTime from);

    /**
     * Consommation des synchros de <b>tout le compte</b> commencées depuis un instant, synchros hors réserve
     * exclues (F-107 / SF-107-04 : la réserve d'essai de la Vigie est une enveloppe par compte).
     */
    @Query("select coalesce(sum(x.consumedTokens), 0) from RadarSync x"
            + " where x.userId = :userId and x.startedAt >= :from and x.reserveExempt = false")
    long sumAccountConsumedSince(@Param("userId") UUID userId, @Param("from") java.time.OffsetDateTime from);

    /** Synchros d'un compte commencées dans une fenêtre (relevé des essais Vigie, F-107 / SF-107-04). */
    List<RadarSync> findByUserIdAndStartedAtBetweenOrderByStartedAtAsc(UUID userId, java.time.OffsetDateTime from,
            java.time.OffsetDateTime to);

    /** Purge du Radar d'un poste (SF-99-05) : suppression en masse, filtrée sur le périmètre. */
    @Modifying
    @Query("delete from RadarSync x where x.userId = :userId and x.hostId = :hostId")
    int purgeScope(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    /** Purge de tous les Radars d'un compte (SF-99-05). */
    @Modifying
    @Query("delete from RadarSync x where x.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
