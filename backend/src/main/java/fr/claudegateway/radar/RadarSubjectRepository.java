package fr.claudegateway.radar;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Sujets du Radar (F-99). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarSubjectRepository extends JpaRepository<RadarSubject, UUID> {

    Optional<RadarSubject> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);

    List<RadarSubject> findByUserIdAndHostId(UUID userId, UUID hostId);

    List<RadarSubject> findByUserIdAndHostIdAndIdIn(UUID userId, UUID hostId, Collection<UUID> ids);

    /** Sujets silencieux d'un poste (SF-99-04). */
    List<RadarSubject> findByUserIdAndHostIdAndStateInAndMergedIntoIdIsNullAndLastActivityAtBefore(
            UUID userId, UUID hostId, Collection<RadarSubjectState> states, OffsetDateTime threshold);

    /**
     * Les <b>périmètres</b> {@code (user_id, host_id)} qui ont des sujets silencieux (SF-99-04). Ne rend
     * aucun sujet : le balayage traite ensuite chaque périmètre avec des requêtes filtrées.
     */
    @Query("select distinct s.userId, s.hostId from RadarSubject s where s.state in :states "
            + "and s.mergedIntoId is null and s.lastActivityAt < :threshold")
    List<Object[]> findScopesWithSilentSubjects(@Param("states") Collection<RadarSubjectState> states,
            @Param("threshold") OffsetDateTime threshold);

    /** Purge du Radar d'un poste (SF-99-05) : suppression en masse, filtrée sur le périmètre. */
    @Modifying
    @Query("delete from RadarSubject x where x.userId = :userId and x.hostId = :hostId")
    int purgeScope(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    /** Purge de tous les Radars d'un compte (SF-99-05). */
    @Modifying
    @Query("delete from RadarSubject x where x.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
