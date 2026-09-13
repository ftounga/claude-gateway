package fr.claudegateway.radar;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Traces des purges (F-99 / SF-99-05). Toute méthode porte {@code user_id}. */
public interface RadarPurgeRepository extends JpaRepository<RadarPurge, UUID> {

    List<RadarPurge> findByUserIdAndHostIdOrderByPurgedAtDesc(UUID userId, UUID hostId);

    /** Les traces disparaissent avec le compte. */
    @Modifying
    @Query("delete from RadarPurge x where x.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
