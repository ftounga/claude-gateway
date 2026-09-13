package fr.claudegateway.radar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Synchros (F-99). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarSyncRepository extends JpaRepository<RadarSync, UUID> {

    Optional<RadarSync> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);

    List<RadarSync> findByUserIdAndHostIdOrderByStartedAtDesc(UUID userId, UUID hostId, Pageable page);

    long deleteByUserIdAndHostId(UUID userId, UUID hostId);

    long deleteByUserId(UUID userId);
}
