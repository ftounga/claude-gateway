package fr.claudegateway.videos;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Les devis de vidéo (F-216). <b>Toute</b> lecture filtre sur {@code user_id}. */
public interface VideoQuoteRepository extends JpaRepository<VideoQuote, UUID> {

    /** Les devis d'un sujet du compte dans un statut, le plus récent d'abord. */
    List<VideoQuote> findByUserIdAndWorkspaceIdAndStatusOrderByCreatedAtDesc(UUID userId, UUID workspaceId,
            VideoQuote.Status status);

    Optional<VideoQuote> findByIdAndUserId(UUID id, UUID userId);
}
