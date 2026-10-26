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

    /** Les versions d'un sujet du compte publiées pendant le tour en cours (F-216 / SF-216-02). */
    @Query("select v from VideoVersion v, Video video where v.videoId = video.id and v.userId = :userId"
            + " and video.userId = :userId and video.workspaceId = :workspaceId and v.costPending = true")
    List<VideoVersion> findPending(@Param("userId") UUID userId, @Param("workspaceId") UUID workspaceId);

    /** Combien de vidéos distinctes du compte ont une version rattachée à ce devis (F-216 / SF-216-02). */
    @Query("select count(distinct v.videoId) from VideoVersion v where v.userId = :userId and v.quoteId = :quoteId")
    long countVideosOfQuote(@Param("userId") UUID userId, @Param("quoteId") UUID quoteId);

    /** Le stockage vidéo + audio du compte (plafond D1). */
    @Query("select coalesce(sum(v.sizeBytes), 0) from VideoVersion v where v.userId = :userId")
    long sumSizeBytesByUserId(@Param("userId") UUID userId);
}
