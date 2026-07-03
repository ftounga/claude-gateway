package fr.claudegateway.upload;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance des métadonnées de fichiers téléversés. Toute lecture propre à un utilisateur passe
 * par une méthode filtrant sur {@code user_id} (isolation multi-tenant). Aucune logique métier ici.
 */
@Repository
public interface UploadedFileRepository extends JpaRepository<UploadedFile, UUID> {

    Optional<UploadedFile> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Fichiers rattachés à une conversation, restreints à l'utilisateur propriétaire (F-23,
     * isolation multi-tenant), du plus récent au plus ancien.
     */
    List<UploadedFile> findByConversationIdAndUserIdOrderByCreatedAtDesc(UUID conversationId, UUID userId);

    /** Export RGPD : toutes les métadonnées de fichiers d'un utilisateur (isolation {@code user_id}). */
    List<UploadedFile> findByUserId(UUID userId);

    /** Suppression RGPD : toutes les métadonnées de fichiers d'un utilisateur. */
    void deleteByUserId(UUID userId);
}
