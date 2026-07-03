package fr.claudegateway.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.upload.UploadedFile;
import fr.claudegateway.upload.UploadedFileRepository;

/**
 * Orchestration des conversations : liste, détail, renommage, suppression, dossier de fichiers. Toute
 * opération est bornée à l'utilisateur courant — l'isolation {@code user_id} est appliquée à chaque
 * accès et une conversation d'autrui est indistincte d'une conversation inexistante ({@code 404}).
 */
@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UploadedFileRepository uploadedFileRepository;

    public ConversationService(ConversationRepository conversationRepository, MessageRepository messageRepository,
            UploadedFileRepository uploadedFileRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.uploadedFileRepository = uploadedFileRepository;
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
     * Fichiers téléversés rattachés à une conversation possédée (F-23), du plus récent au plus ancien.
     * L'appartenance de la conversation est vérifiée d'abord ({@code 404} sinon), puis les fichiers
     * sont lus avec le double filtre {@code conversation_id} + {@code user_id} (isolation multi-tenant).
     *
     * @throws ConversationNotFoundException si la conversation est absente ou appartient à un autre utilisateur
     */
    @Transactional(readOnly = true)
    public List<UploadedFile> filesOf(UUID conversationId, UUID userId) {
        Conversation conversation = getOwned(conversationId, userId);
        return uploadedFileRepository.findByConversationIdAndUserIdOrderByCreatedAtDesc(conversation.getId(), userId);
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
