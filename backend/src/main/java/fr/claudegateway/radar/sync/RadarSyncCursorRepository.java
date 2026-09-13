package fr.claudegateway.radar.sync;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Curseurs de collecte (F-100). Toute méthode porte {@code user_id} et {@code host_id}, sauf la purge d'un compte. */
public interface RadarSyncCursorRepository extends JpaRepository<RadarSyncCursor, UUID> {

    Optional<RadarSyncCursor> findByUserIdAndHostIdAndSourceAndConversationRef(UUID userId, UUID hostId, String source,
            String conversationRef);

    /** Les curseurs les plus récents d'une source, pour l'entrée de la collecte. */
    List<RadarSyncCursor> findByUserIdAndHostIdAndSourceOrderByCursorAtDesc(UUID userId, UUID hostId, String source,
            Pageable page);

    @Modifying
    @Query("delete from RadarSyncCursor c where c.userId = :userId and c.hostId = :hostId")
    int purgeScope(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    @Modifying
    @Query("delete from RadarSyncCursor c where c.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
