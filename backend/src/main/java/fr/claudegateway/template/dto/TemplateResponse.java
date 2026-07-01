package fr.claudegateway.template.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.template.PromptTemplate;
import fr.claudegateway.template.TemplateCategory;

/**
 * Représentation publique d'un modèle de prompt renvoyée par l'API {@code /api/templates}.
 * N'expose aucun identifiant technique interne autre que l'id du modèle (le {@code user_id} du
 * propriétaire n'est jamais renvoyé : il est implicite au contexte de sécurité).
 */
public record TemplateResponse(
        UUID id,
        String name,
        TemplateCategory category,
        String content,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static TemplateResponse from(PromptTemplate template) {
        return new TemplateResponse(
                template.getId(),
                template.getName(),
                template.getCategory(),
                template.getContent(),
                template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
