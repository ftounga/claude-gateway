package fr.claudegateway.ai;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration du fournisseur Anthropic (mode Hosted). Toutes les valeurs sont externalisées ;
 * la clé plateforme provient exclusivement de l'environnement et n'est jamais journalisée.
 *
 * @param apiKey       clé API plateforme (env {@code ANTHROPIC_API_KEY}) — vide => fournisseur dormant (503)
 * @param baseUrl      base de l'API Anthropic
 * @param version      valeur de l'en-tête {@code anthropic-version}
 * @param defaultModel modèle utilisé lorsque la requête n'en précise pas
 * @param models       liste blanche des modèles sélectionnables
 * @param maxTokens    plafond de tokens de sortie par appel
 * @param timeout      délai maximal d'un appel amont
 */
@ConfigurationProperties(prefix = "app.ai.anthropic")
public record AnthropicProperties(
        String apiKey,
        String baseUrl,
        String version,
        String defaultModel,
        List<String> models,
        Integer maxTokens,
        Duration timeout) {

    public AnthropicProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.anthropic.com";
        }
        if (version == null || version.isBlank()) {
            version = "2023-06-01";
        }
        if (defaultModel == null || defaultModel.isBlank()) {
            defaultModel = "claude-opus-4-8";
        }
        if (models == null || models.isEmpty()) {
            models = List.of("claude-opus-4-8", "claude-sonnet-5", "claude-haiku-4-5");
        }
        if (maxTokens == null || maxTokens <= 0) {
            maxTokens = 4096;
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(120);
        }
    }

    /** Vrai si une clé plateforme est configurée (fournisseur réellement appelable). */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
