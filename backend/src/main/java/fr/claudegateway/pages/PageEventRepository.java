package fr.claudegateway.pages;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistance du journal des pages (F-109 / SF-109-05). */
@Repository
public interface PageEventRepository extends JpaRepository<PageEvent, UUID> {

    /** Le journal d'une page, le plus récent d'abord (isolation {@code user_id}). */
    List<PageEvent> findByPageIdAndUserIdOrderByOccurredAtDesc(UUID pageId, UUID userId, Pageable pageable);
}
