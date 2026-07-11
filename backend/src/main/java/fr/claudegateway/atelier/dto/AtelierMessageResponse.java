package fr.claudegateway.atelier.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.atelier.AtelierMessage;

/** Vue d'un message Atelier exposée au client (F-28 / SF-28-02). */
public record AtelierMessageResponse(UUID id, String role, String content, OffsetDateTime createdAt) {

    public static AtelierMessageResponse from(AtelierMessage message) {
        return new AtelierMessageResponse(
                message.getId(), message.getRole(), message.getContent(), message.getCreatedAt());
    }
}
