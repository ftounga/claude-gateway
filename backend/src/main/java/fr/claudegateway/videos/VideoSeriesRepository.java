package fr.claudegateway.videos;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Les séries, toujours lues avec leur propriétaire (F-213). */
@Repository
public interface VideoSeriesRepository extends JpaRepository<VideoSeries, UUID> {

    Optional<VideoSeries> findByIdAndUserId(UUID id, UUID userId);

    List<VideoSeries> findByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);

    List<VideoSeries> findByUserIdAndHostId(UUID userId, UUID hostId);
}
