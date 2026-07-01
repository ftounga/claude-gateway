package fr.claudegateway.chat.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.chat.Conversation;

/**
 * Vue résumée d'une conversation (liste latérale), sans les messages.
 */
public record ConversationSummaryResponse(
        UUID id,
        String title,
        String model,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ConversationSummaryResponse from(Conversation conversation) {
        return new ConversationSummaryResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getModel(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}
