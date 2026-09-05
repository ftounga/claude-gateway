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
 * @param maxTokens      plafond de tokens de sortie par appel de <b>chat</b> (F-02)
 * @param agentMaxTokens plafond de tokens de sortie par appel de la <b>boucle d'agent</b> (F-28).
 *                       Distinct de {@code maxTokens} : l'agent transporte le contenu entier d'un
 *                       fichier dans sa sortie ({@code write_file}), le chat non. Les relier ferait
 *                       porter au chat, livré et stable, un besoin qui n'est pas le sien.
 * @param timeout        délai maximal d'un appel amont
 */
@ConfigurationProperties(prefix = "app.ai.anthropic")
public record AnthropicProperties(
        String apiKey,
        String baseUrl,
        String version,
        String defaultModel,
        List<String> models,
        Integer maxTokens,
        Integer agentMaxTokens,
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
        // 16 384 et non 64 000 : au-delà, l'appel doit être streamé vers le fournisseur pour ne pas
        // heurter les délais HTTP, or la boucle d'agent appelle en non-streamé (SF-28-18, décision D2).
        if (agentMaxTokens == null || agentMaxTokens <= 0) {
            agentMaxTokens = 16_384;
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
