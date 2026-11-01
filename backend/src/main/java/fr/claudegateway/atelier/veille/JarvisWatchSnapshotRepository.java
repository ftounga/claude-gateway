package fr.claudegateway.atelier.veille;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Les instantanés des veilles — toujours sous {@code user_id} (F-230 / SF-230-02). */
public interface JarvisWatchSnapshotRepository extends JpaRepository<JarvisWatchSnapshot, UUID> {

    Optional<JarvisWatchSnapshot> findByWatchIdAndUserId(UUID watchId, UUID userId);

    /** Une veille reproposée repart d'une base neuve. */
    @Modifying
    @Query("delete from JarvisWatchSnapshot s where s.watchId = :watchId and s.userId = :userId")
    int deleteForWatch(@Param("watchId") UUID watchId, @Param("userId") UUID userId);
}
