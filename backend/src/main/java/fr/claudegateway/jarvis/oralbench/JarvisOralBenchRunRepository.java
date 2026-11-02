package fr.claudegateway.jarvis.oralbench;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Les passages du banc de la parole (F-240 / SF-240-02) — toujours lus sous {@code user_id}. */
public interface JarvisOralBenchRunRepository extends JpaRepository<JarvisOralBenchRun, UUID> {

    Optional<JarvisOralBenchRun> findByIdAndUserId(UUID id, UUID userId);

    List<JarvisOralBenchRun> findByUserIdOrderByStartedAtDesc(UUID userId);
}
