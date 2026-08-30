package fr.claudegateway.chat;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance des liens message ↔ document de bibliothèque (F-24 / SF-24-03). Aucune logique métier.
 */
@Repository
public interface MessageLibraryDocumentRepository extends JpaRepository<MessageLibraryDocument, UUID> {

    /**
     * Liens rattachés à un ensemble de messages, du plus ancien au plus récent. Chargement groupé
     * (évite le N+1) à la reconstruction de l'historique d'une conversation.
     */
    List<MessageLibraryDocument> findByMessageIdInOrderByCreatedAtAsc(Collection<UUID> messageIds);

    /**
     * Purge à la suppression du compte (SF-11-03). Le lien ne porte pas d'{@code user_id} : on passe
     * par les documents de l'utilisateur, qui en portent un.
     */
    void deleteByDocumentIdIn(Collection<UUID> documentIds);
}
