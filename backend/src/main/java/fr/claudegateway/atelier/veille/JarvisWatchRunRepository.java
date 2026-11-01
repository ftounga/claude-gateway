package fr.claudegateway.atelier.veille;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Le journal des passages — toujours sous {@code user_id} (F-230 / SF-230-02). */
public interface JarvisWatchRunRepository extends JpaRepository<JarvisWatchRun, UUID> {

    List<JarvisWatchRun> findByWatchIdAndUserIdOrderByStartedAtDesc(UUID watchId, UUID userId, Pageable page);

    /** Les passages d'une fenêtre, pour ce compte — la mesure d'adoption (SF-230-04). */
    List<JarvisWatchRun> findByUserIdAndStartedAtGreaterThanEqual(UUID userId, OffsetDateTime since);

    /** Purge des passages anciens. */
    @Modifying
    @Query("delete from JarvisWatchRun r where r.startedAt < :before")
    int purgeBefore(@Param("before") OffsetDateTime before);
}
