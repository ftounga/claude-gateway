package fr.claudegateway.template.dto;

import fr.claudegateway.template.TemplateCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /api/templates} et {@code PUT /api/templates/{id}} : nom, catégorie
 * (optionnelle, défaut {@code OTHER} côté service) et contenu du modèle de prompt.
 */
public record TemplateRequest(
        @NotBlank(message = "Le nom est requis.")
        @Size(max = 120, message = "Le nom est trop long.")
        String name,

        /** Optionnelle : si absente, le service applique {@link TemplateCategory#OTHER}. */
        TemplateCategory category,

        @NotBlank(message = "Le contenu est requis.")
        @Size(max = 10000, message = "Le contenu est trop long.")
        String content) {
}
