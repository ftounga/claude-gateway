package fr.claudegateway.rag.provider;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration du fournisseur d'embeddings (F-06). Toutes les valeurs sont externalisées ; la clé
 * API provient exclusivement de l'environnement et n'est jamais journalisée.
 *
 * @param provider  fournisseur actif : {@code stub} (défaut, déterministe, sans réseau) ou {@code api}
 * @param dimension dimension des vecteurs (OQ-01, défaut 1536 — doit correspondre à {@code vector(N)})
 * @param apiKey    clé API (env, ex. {@code APP_RAG_EMBEDDING_API_KEY}) — vide => fournisseur api dormant
 * @param baseUrl   base de l'API d'embeddings (format OpenAI-compatible {@code POST /embeddings})
 * @param model     identifiant du modèle d'embeddings côté fournisseur
 * @param timeout   délai maximal d'un appel amont
 */
@ConfigurationProperties(prefix = "app.rag.embedding")
public record EmbeddingProperties(
        String provider,
        Integer dimension,
        String apiKey,
        String baseUrl,
        String model,
        Duration timeout) {

    public static final int DEFAULT_DIMENSION = 1536;

    public EmbeddingProperties {
        if (provider == null || provider.isBlank()) {
            provider = "stub";
        }
        if (dimension == null || dimension <= 0) {
            dimension = DEFAULT_DIMENSION;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        if (model == null || model.isBlank()) {
            model = "text-embedding-3-small";
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(60);
        }
    }

    /** Vrai si une clé API est configurée (fournisseur api réellement appelable). */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
