package fr.claudegateway.videos;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Les retours à la seconde, toujours lus avec leur propriétaire et leur vidéo (F-214 / SF-214-01). */
@Repository
public interface VideoCommentRepository extends JpaRepository<VideoComment, UUID> {

    Optional<VideoComment> findByIdAndUserIdAndVideoId(UUID id, UUID userId, UUID videoId);

    List<VideoComment> findByUserIdAndVideoIdOrderByVersionAscStartMsAscCreatedAtAsc(UUID userId, UUID videoId);

    List<VideoComment> findByUserIdAndVideoIdAndVersionOrderByStartMsAscCreatedAtAsc(UUID userId, UUID videoId,
            int version);

    long countByUserIdAndVideoId(UUID userId, UUID videoId);
}
