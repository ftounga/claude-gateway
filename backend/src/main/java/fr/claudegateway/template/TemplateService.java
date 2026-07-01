package fr.claudegateway.template;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration des modèles de prompts (F-13) : liste, détail, création, mise à jour, suppression.
 * Toute opération est bornée à l'utilisateur courant — l'isolation {@code user_id} est appliquée à
 * chaque accès et un modèle d'autrui est indistinct d'un modèle inexistant ({@code 404}).
 */
@Service
public class TemplateService {

    private final TemplateRepository templateRepository;

    public TemplateService(TemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    /** Modèles de l'utilisateur, du plus récemment modifié au plus ancien. */
    @Transactional(readOnly = true)
    public List<PromptTemplate> listForUser(UUID userId) {
        return templateRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /**
     * Modèle appartenant à l'utilisateur.
     *
     * @throws TemplateNotFoundException si absent ou appartenant à un autre utilisateur
     */
    @Transactional(readOnly = true)
    public PromptTemplate getOwned(UUID id, UUID userId) {
        return templateRepository.findByIdAndUserId(id, userId)
                .orElseThrow(TemplateNotFoundException::new);
    }

    /**
     * Crée un modèle rattaché à l'utilisateur courant. {@code name}/{@code content} sont normalisés
     * ({@code trim()}) ; {@code category} vaut {@link TemplateCategory#OTHER} si non fournie.
     */
    @Transactional
    public PromptTemplate create(UUID userId, String name, TemplateCategory category, String content) {
        PromptTemplate template = PromptTemplate.builder()
                .userId(userId)
                .name(name.trim())
                .category(category != null ? category : TemplateCategory.OTHER)
                .content(content.trim())
                .build();
        return templateRepository.save(template);
    }

    /**
     * Met à jour un modèle possédé par l'utilisateur.
     *
     * @throws TemplateNotFoundException si absent ou appartenant à un autre utilisateur
     */
    @Transactional
    public PromptTemplate update(UUID id, UUID userId, String name, TemplateCategory category, String content) {
        PromptTemplate template = getOwned(id, userId);
        template.setName(name.trim());
        template.setCategory(category != null ? category : TemplateCategory.OTHER);
        template.setContent(content.trim());
        return templateRepository.save(template);
    }

    /**
     * Supprime définitivement un modèle possédé.
     *
     * @throws TemplateNotFoundException si absent ou appartenant à un autre utilisateur
     */
    @Transactional
    public void delete(UUID id, UUID userId) {
        PromptTemplate template = getOwned(id, userId);
        templateRepository.delete(template);
    }
}
