package fr.claudegateway.videos;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Les vidéos, toujours lues avec leur propriétaire (F-213 / SF-213-01). */
@Repository
public interface VideoRepository extends JpaRepository<Video, UUID> {

    Optional<Video> findByIdAndUserId(UUID id, UUID userId);

    List<Video> findByUserIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(UUID userId, UUID workspaceId, VideoKind kind);

    List<Video> findByUserIdAndHostIdAndKindOrderByCreatedAtDesc(UUID userId, UUID hostId, VideoKind kind);

    List<Video> findByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);

    List<Video> findByUserIdAndSeriesIdOrderByEpisodeAscCreatedAtAsc(UUID userId, UUID seriesId);

    @Query("select coalesce(max(v.episode), 0) from Video v where v.userId = :userId and v.seriesId = :seriesId")
    int maxEpisode(@Param("userId") UUID userId, @Param("seriesId") UUID seriesId);
}
