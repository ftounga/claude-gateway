package fr.claudegateway.radar;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Sujets du Radar (F-99). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarSubjectRepository extends JpaRepository<RadarSubject, UUID> {

    Optional<RadarSubject> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);

    List<RadarSubject> findByUserIdAndHostId(UUID userId, UUID hostId);

    List<RadarSubject> findByUserIdAndHostIdAndIdIn(UUID userId, UUID hostId, Collection<UUID> ids);

    long deleteByUserIdAndHostId(UUID userId, UUID hostId);

    long deleteByUserId(UUID userId);
}
