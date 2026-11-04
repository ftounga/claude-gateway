package fr.claudegateway.jarvis.companion;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Les sessions émises aux compagnons (F-232 / SF-232-05) — mesure seulement, toujours filtrée par compte. */
public interface JarvisCompanionOpeningRepository extends JpaRepository<JarvisCompanionOpening, UUID> {

    List<JarvisCompanionOpening> findByUserIdOrderByCreatedAtAsc(UUID userId);
}
