package fr.claudegateway.jarvis.capture;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Les captures d'énoncés (F-239 / SF-239-03) — toujours lues sous {@code user_id}. */
public interface JarvisAudioCaptureRepository extends JpaRepository<JarvisAudioCapture, UUID> {

    List<JarvisAudioCapture> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<JarvisAudioCapture> findByIdAndUserId(UUID id, UUID userId);

    List<JarvisAudioCapture> findByCreatedAtBefore(OffsetDateTime limit);
}
