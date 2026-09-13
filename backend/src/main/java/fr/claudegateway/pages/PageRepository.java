package fr.claudegateway.pages;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistance des pages (F-109). Toute lecture filtre {@code user_id} ; aucune logique métier ici. */
@Repository
public interface PageRepository extends JpaRepository<Page, UUID> {

    /** Lecture isolée : une page n'est visible que par son propriétaire. */
    Optional<Page> findByIdAndUserId(UUID id, UUID userId);
}
