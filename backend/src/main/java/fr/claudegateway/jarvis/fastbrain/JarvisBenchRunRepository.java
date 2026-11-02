package fr.claudegateway.jarvis.fastbrain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Les passages du banc (F-239 / SF-239-13) — toujours lus sous {@code user_id}. */
public interface JarvisBenchRunRepository extends JpaRepository<JarvisBenchRun, UUID> {

    Optional<JarvisBenchRun> findByIdAndUserId(UUID id, UUID userId);

    List<JarvisBenchRun> findByUserIdOrderByStartedAtDesc(UUID userId);
}
