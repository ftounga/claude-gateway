package fr.claudegateway.jarvis.state;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Les réglages du registre par client (F-247 / SF-247-02). Lecture sous {@code user_id} + {@code host_id}. */
public interface JarvisStateSettingsRepository extends JpaRepository<JarvisStateSettings, UUID> {

    Optional<JarvisStateSettings> findByUserIdAndHostId(UUID userId, UUID hostId);

    List<JarvisStateSettings> findByUserId(UUID userId);

    /** Les clients à rétention propre, pour la purge nocturne. */
    List<JarvisStateSettings> findByFactRetentionDaysNot(int days);
}
