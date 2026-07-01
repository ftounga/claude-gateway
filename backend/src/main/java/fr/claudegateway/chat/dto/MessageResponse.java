package fr.claudegateway.chat.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.chat.Message;
import fr.claudegateway.chat.MessageRole;

/**
 * Vue publique d'un message.
 */
public record MessageResponse(
        UUID id,
        MessageRole role,
        String content,
        String model,
        OffsetDateTime createdAt) {

    public static MessageResponse from(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getModel(),
                message.getCreatedAt());
    }
}
