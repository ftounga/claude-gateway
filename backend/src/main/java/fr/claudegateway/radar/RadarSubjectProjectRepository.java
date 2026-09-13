package fr.claudegateway.radar;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Liens sujet ↔ projet (F-106 / SF-106-06). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarSubjectProjectRepository extends JpaRepository<RadarSubjectProject, UUID> {

    List<RadarSubjectProject> findByUserIdAndHostIdAndSubjectId(UUID userId, UUID hostId, UUID subjectId);

    Optional<RadarSubjectProject> findByUserIdAndHostIdAndSubjectIdAndWorkspaceId(
            UUID userId, UUID hostId, UUID subjectId, UUID workspaceId);

    List<RadarSubjectProject> findByUserIdAndHostIdAndSubjectIdAndWorkspaceIdIn(
            UUID userId, UUID hostId, UUID subjectId, Collection<UUID> workspaceIds);

    List<RadarSubjectProject> findByUserIdAndHostIdAndState(UUID userId, UUID hostId,
            RadarSubjectProjectState state);

    /** Purge du Radar d'un poste : suppression en masse, filtrée sur le périmètre. */
    @Modifying
    @Query("delete from RadarSubjectProject x where x.userId = :userId and x.hostId = :hostId")
    int purgeScope(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    /** Purge de tous les Radars d'un compte. */
    @Modifying
    @Query("delete from RadarSubjectProject x where x.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
