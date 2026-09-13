package fr.claudegateway.radar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Journal des corrections (F-99 / SF-99-02). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarCorrectionRepository extends JpaRepository<RadarCorrection, UUID> {

    Optional<RadarCorrection> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);

    List<RadarCorrection> findByUserIdAndHostIdOrderByCreatedAtDesc(UUID userId, UUID hostId);

    List<RadarCorrection> findByUserIdAndHostIdAndSubjectIdOrderByCreatedAtDesc(
            UUID userId, UUID hostId, UUID subjectId);

    List<RadarCorrection> findByUserIdAndHostIdAndTargetIdAndUndoneAtIsNull(
            UUID userId, UUID hostId, UUID targetId);

    long deleteByUserIdAndHostId(UUID userId, UUID hostId);

    long deleteByUserId(UUID userId);
}
