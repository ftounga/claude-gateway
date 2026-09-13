package fr.claudegateway.radar;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Annuaire du Radar (F-99). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarPersonRepository extends JpaRepository<RadarPerson, UUID> {

    Optional<RadarPerson> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);

    Optional<RadarPerson> findByUserIdAndHostIdAndSourceKey(UUID userId, UUID hostId, String sourceKey);

    List<RadarPerson> findByUserIdAndHostIdOrderByDisplayNameAsc(UUID userId, UUID hostId);

    List<RadarPerson> findByUserIdAndHostIdAndIdIn(UUID userId, UUID hostId, Collection<UUID> ids);

    long deleteByUserIdAndHostId(UUID userId, UUID hostId);

    long deleteByUserId(UUID userId);
}
