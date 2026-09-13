package fr.claudegateway.radar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Rôles par sujet (F-99). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarSubjectRoleRepository extends JpaRepository<RadarSubjectRole, UUID> {

    Optional<RadarSubjectRole> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);

    Optional<RadarSubjectRole> findByUserIdAndHostIdAndSubjectIdAndPersonId(
            UUID userId, UUID hostId, UUID subjectId, UUID personId);

    List<RadarSubjectRole> findByUserIdAndHostIdAndSubjectId(UUID userId, UUID hostId, UUID subjectId);

    List<RadarSubjectRole> findByUserIdAndHostId(UUID userId, UUID hostId);

    long deleteByUserIdAndHostId(UUID userId, UUID hostId);

    long deleteByUserId(UUID userId);
}
