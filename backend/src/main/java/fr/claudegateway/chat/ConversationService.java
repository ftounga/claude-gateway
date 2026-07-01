package fr.claudegateway.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration des conversations : liste, détail, renommage, suppression. Toute opération est
 * bornée à l'utilisateur courant — l'isolation {@code user_id} est appliquée à chaque accès et
 * une conversation d'autrui est indistincte d'une conversation inexistante ({@code 404}).
 */
@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public ConversationService(ConversationRepository conversationRepository, MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    /** Conversations de l'utilisateur, de la plus récemment active à la plus ancienne. */
    @Transactional(readOnly = true)
    public List<Conversation> listForUser(UUID userId) {
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /**
     * Conversation appartenant à l'utilisateur.
     *
     * @throws ConversationNotFoundException si absente ou appartenant à un autre utilisateur
     */
    @Transactional(readOnly = true)
    public Conversation getOwned(UUID conversationId, UUID userId) {
        return conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(ConversationNotFoundException::new);
    }

    /** Messages ordonnés d'une conversation possédée par l'utilisateur. */
    @Transactional(readOnly = true)
    public List<Message> messagesOf(UUID conversationId, UUID userId) {
        Conversation conversation = getOwned(conversationId, userId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
    }

    /**
     * Renomme une conversation possédée par l'utilisateur.
     *
     * @throws ConversationNotFoundException si absente ou appartenant à un autre utilisateur
     */
    @Transactional
    public Conversation rename(UUID conversationId, UUID userId, String title) {
        Conversation conversation = getOwned(conversationId, userId);
        conversation.setTitle(title.trim());
        return conversationRepository.save(conversation);
    }

    /**
     * Supprime une conversation possédée (cascade sur les messages via la contrainte FK).
     *
     * @throws ConversationNotFoundException si absente ou appartenant à un autre utilisateur
     */
    @Transactional
    public void delete(UUID conversationId, UUID userId) {
        Conversation conversation = getOwned(conversationId, userId);
        conversationRepository.delete(conversation);
    }
}
