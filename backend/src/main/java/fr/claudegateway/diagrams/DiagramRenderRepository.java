package fr.claudegateway.diagrams;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** F-237 / SF-237-03 — les schémas conservés, toujours lus avec {@code user_id}. */
public interface DiagramRenderRepository extends JpaRepository<DiagramRender, UUID> {

    Optional<DiagramRender> findByIdAndUserId(UUID id, UUID userId);

    List<DiagramRender> findTop50ByUserIdAndWorkspaceIdOrderByCreatedAtDesc(UUID userId, UUID workspaceId);
}
