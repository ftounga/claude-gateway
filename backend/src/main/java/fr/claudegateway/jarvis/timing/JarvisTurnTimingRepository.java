package fr.claudegateway.jarvis.timing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Les tours vocaux chronométrés (F-233 / SF-233-01) — lectures toujours sous {@code user_id}. */
public interface JarvisTurnTimingRepository extends JpaRepository<JarvisTurnTiming, UUID> {

    List<JarvisTurnTiming> findByUserIdAndCreatedAtGreaterThanEqual(UUID userId, OffsetDateTime from);

    /** Purge des lignes plus vieilles que {@code before} (tous comptes : maintenance). */
    @Modifying
    @Transactional
    @Query("delete from JarvisTurnTiming t where t.createdAt < :before")
    int deleteOlderThan(@Param("before") OffsetDateTime before);
}
