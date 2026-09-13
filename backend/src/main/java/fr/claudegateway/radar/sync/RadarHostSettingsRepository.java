package fr.claudegateway.radar.sync;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Réglages Radar des postes (F-100). Toute méthode porte {@code user_id} et {@code host_id}, sauf la purge d'un compte. */
public interface RadarHostSettingsRepository extends JpaRepository<RadarHostSettings, UUID> {

    Optional<RadarHostSettings> findByUserIdAndHostId(UUID userId, UUID hostId);

    /** Les postes activés, par page (planificateur, SF-100-02). */
    List<RadarHostSettings> findByEnabledTrueOrderByIdAsc(Pageable page);

    /**
     * <b>Le verrou</b> : prend le poste pour une synchro s'il est libre. Rend 1 si pris, 0 si une synchro
     * tient déjà le poste — un seul démarrage gagne, tous pods confondus.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RadarHostSettings s set s.runningSyncId = :syncId"
            + " where s.userId = :userId and s.hostId = :hostId and s.runningSyncId is null")
    int claim(@Param("userId") UUID userId, @Param("hostId") UUID hostId, @Param("syncId") UUID syncId);

    /** Rend le poste, s'il est encore tenu par cette synchro. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RadarHostSettings s set s.runningSyncId = null"
            + " where s.userId = :userId and s.hostId = :hostId and s.runningSyncId = :syncId")
    int release(@Param("userId") UUID userId, @Param("hostId") UUID hostId, @Param("syncId") UUID syncId);

    /** Purge du Radar d'un poste (SF-99-05). */
    @Modifying
    @Query("delete from RadarHostSettings s where s.userId = :userId and s.hostId = :hostId")
    int purgeScope(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    /** Purge de tous les Radars d'un compte (SF-99-05). */
    @Modifying
    @Query("delete from RadarHostSettings s where s.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
