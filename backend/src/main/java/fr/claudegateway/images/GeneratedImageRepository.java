package fr.claudegateway.images;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Accès aux images générées (F-142 / SF-142-04). <b>Toute</b> lecture par identifiant passe par
 * {@code findByIdAndUserId} : l'image d'un autre compte est introuvable (isolation {@code user_id}).
 */
public interface GeneratedImageRepository extends JpaRepository<GeneratedImage, UUID> {

    /** Une image, scellée par le propriétaire. */
    Optional<GeneratedImage> findByIdAndUserId(UUID id, UUID userId);

    /** Les images d'un lieu (poste/client, espace), la plus récente d'abord. */
    List<GeneratedImage> findByUserIdAndHostIdAndSpaceOrderByCreatedAtDesc(UUID userId, UUID hostId,
            ImageSpace space);

    /** Nombre d'images REELLES (READY) d'un compte — sert au quota. */
    long countByUserIdAndStatus(UUID userId, GeneratedImageStatus status);
}
