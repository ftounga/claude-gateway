package fr.claudegateway.ai;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Implémentation du {@link ModelCatalog} adossée à la configuration Anthropic (mode Hosted, V1).
 * Seul point qui relie le catalogue à Anthropic : le domaine ne voit que {@link ModelCatalog}.
 */
@Component
public class AnthropicModelCatalog implements ModelCatalog {

    private final AnthropicProperties properties;

    public AnthropicModelCatalog(AnthropicProperties properties) {
        this.properties = properties;
    }

    @Override
    public String defaultModel() {
        return properties.defaultModel();
    }

    @Override
    public List<String> availableModels() {
        return properties.models();
    }
}
