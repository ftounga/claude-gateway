package fr.claudegateway.ocr;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance des documents OCR. Toute lecture propre à un utilisateur passe par une méthode
 * filtrant sur {@code user_id} (isolation multi-tenant). Aucune logique métier ici.
 */
@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /** Lecture isolée : un document n'est visible que par son propriétaire. */
    Optional<Document> findByIdAndUserId(UUID id, UUID userId);

    /** Liste des documents d'un utilisateur, les plus récents d'abord (isolation {@code user_id}). */
    List<Document> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Documents en attente de complétion d'un job OCR asynchrone (worker de polling, SF-05-02). */
    List<Document> findByStatus(DocumentStatus status);
}
