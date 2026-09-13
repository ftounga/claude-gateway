package fr.claudegateway.pages;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistance des pages (F-109). Toute lecture filtre {@code user_id} ; aucune logique métier ici. */
@Repository
public interface PageRepository extends JpaRepository<Page, UUID> {

    /** Lecture isolée : une page n'est visible que par son propriétaire. */
    Optional<Page> findByIdAndUserId(UUID id, UUID userId);

    /** Les pages d'un lieu (poste ou client, espace), la plus récemment modifiée d'abord (SF-109-04). */
    List<Page> findByUserIdAndHostIdAndSpaceOrderByUpdatedAtDesc(UUID userId, UUID hostId, PageSpace space);
}
