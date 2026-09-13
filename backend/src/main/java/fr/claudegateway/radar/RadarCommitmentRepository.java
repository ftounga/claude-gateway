package fr.claudegateway.radar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Engagements (F-99). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarCommitmentRepository extends JpaRepository<RadarCommitment, UUID> {

    Optional<RadarCommitment> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);

    Optional<RadarCommitment> findByUserIdAndHostIdAndExtractionKey(
            UUID userId, UUID hostId, String extractionKey);

    List<RadarCommitment> findByUserIdAndHostIdAndSubjectId(UUID userId, UUID hostId, UUID subjectId);

    List<RadarCommitment> findByUserIdAndHostId(UUID userId, UUID hostId);

    /** Purge du Radar d'un poste (SF-99-05) : suppression en masse, filtrée sur le périmètre. */
    @Modifying
    @Query("delete from RadarCommitment x where x.userId = :userId and x.hostId = :hostId")
    int purgeScope(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    /** Purge de tous les Radars d'un compte (SF-99-05). */
    @Modifying
    @Query("delete from RadarCommitment x where x.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
