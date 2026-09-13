package fr.claudegateway.radar;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Phrases des résumés (F-99). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarSubjectFactRepository extends JpaRepository<RadarSubjectFact, UUID> {

    List<RadarSubjectFact> findByUserIdAndHostIdAndSubjectIdOrderByPositionAsc(
            UUID userId, UUID hostId, UUID subjectId);

    long deleteByUserIdAndHostIdAndSubjectId(UUID userId, UUID hostId, UUID subjectId);

    long deleteByUserIdAndHostId(UUID userId, UUID hostId);

    long deleteByUserId(UUID userId);
}
