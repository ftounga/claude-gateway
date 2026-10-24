package fr.claudegateway.videos;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Les versions des vidéos, toujours lues avec leur propriétaire (F-213 / SF-213-01). */
@Repository
public interface VideoVersionRepository extends JpaRepository<VideoVersion, UUID> {

    List<VideoVersion> findByVideoIdAndUserIdOrderByVersionAsc(UUID videoId, UUID userId);

    Optional<VideoVersion> findByVideoIdAndUserIdAndVersion(UUID videoId, UUID userId, int version);

    /** Le stockage vidéo + audio du compte (plafond D1). */
    @Query("select coalesce(sum(v.sizeBytes), 0) from VideoVersion v where v.userId = :userId")
    long sumSizeBytesByUserId(@Param("userId") UUID userId);
}
