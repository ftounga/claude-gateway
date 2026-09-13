package fr.claudegateway.radar;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Liens preuve → fait (F-99). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarEvidenceLinkRepository extends JpaRepository<RadarEvidenceLink, UUID> {

    List<RadarEvidenceLink> findByUserIdAndHostIdAndSubjectId(UUID userId, UUID hostId, UUID subjectId);

    List<RadarEvidenceLink> findByUserIdAndHostIdAndSubjectIdAndTargetKind(
            UUID userId, UUID hostId, UUID subjectId, RadarLinkKind targetKind);

    List<RadarEvidenceLink> findByUserIdAndHostIdAndTargetKindAndTargetId(
            UUID userId, UUID hostId, RadarLinkKind targetKind, UUID targetId);

    List<RadarEvidenceLink> findByUserIdAndHostId(UUID userId, UUID hostId);

    /** Purge du Radar d'un poste (SF-99-05) : suppression en masse, filtrée sur le périmètre. */
    @Modifying
    @Query("delete from RadarEvidenceLink x where x.userId = :userId and x.hostId = :hostId")
    int purgeScope(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    /** Purge de tous les Radars d'un compte (SF-99-05). */
    @Modifying
    @Query("delete from RadarEvidenceLink x where x.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
