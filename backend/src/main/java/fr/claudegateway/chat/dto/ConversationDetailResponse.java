package fr.claudegateway.chat.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.chat.Conversation;
import fr.claudegateway.chat.Message;

/**
 * Vue détaillée d'une conversation : métadonnées + fil complet des messages.
 */
public record ConversationDetailResponse(
        UUID id,
        String title,
        String model,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<MessageResponse> messages) {

    public static ConversationDetailResponse from(Conversation conversation, List<Message> messages) {
        return new ConversationDetailResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getModel(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt(),
                messages.stream().map(MessageResponse::from).toList());
    }
}
