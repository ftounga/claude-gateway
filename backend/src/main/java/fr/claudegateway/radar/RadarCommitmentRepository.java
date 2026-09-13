package fr.claudegateway.radar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Engagements (F-99). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarCommitmentRepository extends JpaRepository<RadarCommitment, UUID> {

    Optional<RadarCommitment> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);

    Optional<RadarCommitment> findByUserIdAndHostIdAndExtractionKey(
            UUID userId, UUID hostId, String extractionKey);

    List<RadarCommitment> findByUserIdAndHostIdAndSubjectId(UUID userId, UUID hostId, UUID subjectId);

    List<RadarCommitment> findByUserIdAndHostId(UUID userId, UUID hostId);

    long deleteByUserIdAndHostId(UUID userId, UUID hostId);

    long deleteByUserId(UUID userId);
}
