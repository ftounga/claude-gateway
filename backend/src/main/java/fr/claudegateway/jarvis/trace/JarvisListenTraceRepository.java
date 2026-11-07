package fr.claudegateway.jarvis.trace;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Le journal de l'écoute (F-233 / SF-233-15) — lectures toujours sous {@code user_id}. */
public interface JarvisListenTraceRepository extends JpaRepository<JarvisListenTraceEvent, UUID> {

    long countByUserIdAndCreatedAtAfter(UUID userId, OffsetDateTime after);

    List<JarvisListenTraceEvent> findByUserIdOrderByCreatedAtAscSeqAsc(UUID userId);

    /** Purge des lignes plus vieilles que {@code before} (tous comptes : maintenance). */
    @Modifying
    @Transactional
    @Query("delete from JarvisListenTraceEvent t where t.createdAt < :before")
    int deleteOlderThan(@Param("before") OffsetDateTime before);
}
