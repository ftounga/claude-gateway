package fr.claudegateway.radar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Journal des corrections (F-99 / SF-99-02). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarCorrectionRepository extends JpaRepository<RadarCorrection, UUID> {

    Optional<RadarCorrection> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);

    List<RadarCorrection> findByUserIdAndHostIdOrderByCreatedAtDesc(UUID userId, UUID hostId);

    List<RadarCorrection> findByUserIdAndHostIdAndSubjectIdOrderByCreatedAtDesc(
            UUID userId, UUID hostId, UUID subjectId);

    List<RadarCorrection> findByUserIdAndHostIdAndTargetIdAndUndoneAtIsNull(
            UUID userId, UUID hostId, UUID targetId);

    /** Les corrections portées par une nouvelle (F-104), plus récentes d'abord. */
    List<RadarCorrection> findByUserIdAndHostIdAndEvidenceIdOrderByCreatedAtDesc(
            UUID userId, UUID hostId, UUID evidenceId);

    /** Purge du Radar d'un poste (SF-99-05) : suppression en masse, filtrée sur le périmètre. */
    @Modifying
    @Query("delete from RadarCorrection x where x.userId = :userId and x.hostId = :hostId")
    int purgeScope(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    /** Purge de tous les Radars d'un compte (SF-99-05). */
    @Modifying
    @Query("delete from RadarCorrection x where x.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
