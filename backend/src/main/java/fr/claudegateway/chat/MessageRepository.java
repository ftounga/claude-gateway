package fr.claudegateway.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance des messages. Les lectures sont bornées par la conversation (elle-même déjà vérifiée
 * comme appartenant à l'utilisateur courant). Aucune logique métier ici.
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    /** Suppression RGPD : tous les messages d'un utilisateur (isolation {@code user_id}). */
    void deleteByUserId(UUID userId);
}
