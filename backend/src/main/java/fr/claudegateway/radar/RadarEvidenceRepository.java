package fr.claudegateway.radar;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Preuves (F-99). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarEvidenceRepository extends JpaRepository<RadarEvidence, UUID> {

    Optional<RadarEvidence> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);

    Optional<RadarEvidence> findByUserIdAndHostIdAndSourceAndSourceRef(
            UUID userId, UUID hostId, RadarEvidenceSource source, String sourceRef);

    List<RadarEvidence> findByUserIdAndHostIdAndIdIn(UUID userId, UUID hostId, Collection<UUID> ids);

    List<RadarEvidence> findByUserIdAndHostId(UUID userId, UUID hostId);

    long deleteByUserIdAndHostId(UUID userId, UUID hostId);

    long deleteByUserId(UUID userId);
}
