package fr.claudegateway.jarvis.fastbrain;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** F-252 / SF-252-02 — les verdicts, toujours lus sous {@code user_id} (sauf la purge des orphelins). */
public interface JarvisFastVerdictRepository extends JpaRepository<JarvisFastVerdict, UUID> {

    Optional<JarvisFastVerdict> findByUserIdAndSpeculationId(UUID userId, UUID speculationId);

    @Modifying
    @Transactional
    @Query("delete from JarvisFastVerdict v where v.id = :id and v.userId = :userId")
    int deleteOwn(@Param("id") UUID id, @Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("delete from JarvisFastVerdict v where v.createdAt < :before")
    int deleteOlderThan(@Param("before") OffsetDateTime before);
}
