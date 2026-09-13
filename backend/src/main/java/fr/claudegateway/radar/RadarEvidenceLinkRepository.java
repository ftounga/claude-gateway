package fr.claudegateway.radar;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Liens preuve → fait (F-99). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarEvidenceLinkRepository extends JpaRepository<RadarEvidenceLink, UUID> {

    List<RadarEvidenceLink> findByUserIdAndHostIdAndSubjectId(UUID userId, UUID hostId, UUID subjectId);

    List<RadarEvidenceLink> findByUserIdAndHostIdAndSubjectIdAndTargetKind(
            UUID userId, UUID hostId, UUID subjectId, RadarLinkKind targetKind);

    List<RadarEvidenceLink> findByUserIdAndHostIdAndTargetKindAndTargetId(
            UUID userId, UUID hostId, RadarLinkKind targetKind, UUID targetId);

    List<RadarEvidenceLink> findByUserIdAndHostId(UUID userId, UUID hostId);

    long deleteByUserIdAndHostId(UUID userId, UUID hostId);

    long deleteByUserId(UUID userId);
}
