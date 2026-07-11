package fr.claudegateway.atelier;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistance des messages Atelier (F-28 / SF-28-02). Lecture toujours filtrée sur {@code user_id}. */
@Repository
public interface AtelierMessageRepository extends JpaRepository<AtelierMessage, UUID> {

    List<AtelierMessage> findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(UUID workspaceId, UUID userId);
}
