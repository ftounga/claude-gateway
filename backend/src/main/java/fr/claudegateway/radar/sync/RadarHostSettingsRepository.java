package fr.claudegateway.radar.sync;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Réglages Radar des postes (F-100). Toute méthode porte {@code user_id} et {@code host_id}, sauf la purge d'un compte. */
public interface RadarHostSettingsRepository extends JpaRepository<RadarHostSettings, UUID> {

    Optional<RadarHostSettings> findByUserIdAndHostId(UUID userId, UUID hostId);

    /** Purge du Radar d'un poste (SF-99-05). */
    @Modifying
    @Query("delete from RadarHostSettings s where s.userId = :userId and s.hostId = :hostId")
    int purgeScope(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    /** Purge de tous les Radars d'un compte (SF-99-05). */
    @Modifying
    @Query("delete from RadarHostSettings s where s.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
