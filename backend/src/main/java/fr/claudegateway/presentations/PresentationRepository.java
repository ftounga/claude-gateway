package fr.claudegateway.presentations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance des présentations (F-129 / SF-129-02). Toute lecture filtre {@code user_id} ; aucune
 * logique métier ici.
 */
@Repository
public interface PresentationRepository extends JpaRepository<Presentation, UUID> {

    /** Lecture isolée : une présentation n'est visible que par son propriétaire. */
    Optional<Presentation> findByIdAndUserId(UUID id, UUID userId);

    /** Les présentations d'un lieu (poste/client, espace), la plus récemment modifiée d'abord. */
    List<Presentation> findByUserIdAndHostIdAndSpaceOrderByUpdatedAtDesc(UUID userId, UUID hostId,
            PresentationSpace space);
}
