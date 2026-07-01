package fr.claudegateway.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ChatMessage;
import fr.claudegateway.ai.ChatRole;
import fr.claudegateway.ai.ModelCatalog;

/**
 * Cœur du proxy de chat Hosted : valide la demande, résout/crée la conversation de l'utilisateur,
 * persiste le message utilisateur, relaie l'historique au fournisseur via l'interface
 * {@link AIProvider} (jamais Anthropic en direct), puis persiste la réponse assistant.
 *
 * <p>Isolation multi-tenant : la conversation ciblée est toujours vérifiée comme appartenant à
 * l'utilisateur courant avant toute écriture ; les messages portent le {@code user_id}.</p>
 */
@Service
public class ChatService {

    private static final int TITLE_MAX_LENGTH = 60;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final AIProvider aiProvider;
    private final ModelCatalog modelCatalog;

    public ChatService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            AIProvider aiProvider,
            ModelCatalog modelCatalog) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.aiProvider = aiProvider;
        this.modelCatalog = modelCatalog;
    }

    /**
     * Traite un tour de chat pour l'utilisateur courant.
     *
     * @param userId         utilisateur authentifié (contexte de sécurité)
     * @param conversationId conversation existante ou null pour en créer une
     * @param rawMessage     message utilisateur (déjà validé non vide côté controller)
     * @param requestedModel modèle souhaité ou null
     * @return le message assistant persisté et la conversation ciblée
     * @throws UnsupportedModelException     si le modèle demandé n'est pas dans la liste blanche
     * @throws ConversationNotFoundException si la conversation ne appartient pas à l'utilisateur
     */
    @Transactional
    public ChatResult reply(UUID userId, UUID conversationId, String rawMessage, String requestedModel) {
        String content = rawMessage.trim();
        // Rejette tout modèle explicite hors liste blanche, même sur une conversation existante.
        validateRequestedModel(requestedModel);
        Conversation conversation = resolveConversation(userId, conversationId, requestedModel, content);

        // Persistance du message utilisateur.
        Message userMessage = messageRepository.save(Message.builder()
                .conversationId(conversation.getId())
                .userId(userId)
                .role(MessageRole.USER)
                .content(content)
                .build());

        // Historique complet (incluant le message tout juste persisté) transmis au fournisseur.
        List<Message> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        ChatCompletionResult completion = aiProvider.complete(
                new ChatCompletionRequest(conversation.getModel(), toProviderMessages(history)));

        // Persistance de la réponse assistant.
        Message assistantMessage = messageRepository.save(Message.builder()
                .conversationId(conversation.getId())
                .userId(userId)
                .role(MessageRole.ASSISTANT)
                .content(completion.content())
                .model(completion.model())
                .build());

        // Rafraîchit updated_at pour le tri de la liste latérale.
        conversation.setTitle(conversation.getTitle());
        conversationRepository.save(conversation);

        return new ChatResult(conversation, userMessage, assistantMessage);
    }

    private Conversation resolveConversation(UUID userId, UUID conversationId, String requestedModel, String firstMessage) {
        if (conversationId != null) {
            return conversationRepository.findByIdAndUserId(conversationId, userId)
                    .orElseThrow(ConversationNotFoundException::new);
        }
        String model = resolveModel(requestedModel);
        return conversationRepository.save(Conversation.builder()
                .userId(userId)
                .title(deriveTitle(firstMessage))
                .model(model)
                .build());
    }

    /**
     * Résout le modèle : celui demandé s'il est dans la liste blanche, sinon le défaut si aucun
     * n'est demandé.
     *
     * @throws UnsupportedModelException si un modèle explicite hors liste blanche est fourni
     */
    private String resolveModel(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            return modelCatalog.defaultModel();
        }
        return requestedModel;
    }

    private void validateRequestedModel(String requestedModel) {
        if (requestedModel != null && !requestedModel.isBlank()
                && !modelCatalog.supports(requestedModel)) {
            throw new UnsupportedModelException("Modèle non supporté : " + requestedModel);
        }
    }

    private String deriveTitle(String message) {
        String singleLine = message.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= TITLE_MAX_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, TITLE_MAX_LENGTH).trim() + "…";
    }

    private List<ChatMessage> toProviderMessages(List<Message> history) {
        List<ChatMessage> messages = new ArrayList<>(history.size());
        for (Message message : history) {
            ChatRole role = message.getRole() == MessageRole.ASSISTANT ? ChatRole.ASSISTANT : ChatRole.USER;
            messages.add(new ChatMessage(role, message.getContent()));
        }
        return messages;
    }

    /** Résultat interne d'un tour de chat. */
    public record ChatResult(Conversation conversation, Message userMessage, Message assistantMessage) {
    }
}
