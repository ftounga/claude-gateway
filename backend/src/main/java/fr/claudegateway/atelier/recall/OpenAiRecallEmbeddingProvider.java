package fr.claudegateway.atelier.recall;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import fr.claudegateway.rag.provider.EmbeddingApiResponse;
import fr.claudegateway.rag.provider.EmbeddingProvider;
import fr.claudegateway.rag.provider.EmbeddingProviderException;
import fr.claudegateway.rag.provider.EmbeddingProviderUnavailableException;

/**
 * Fournisseur d'embeddings <b>OpenAI-compatible</b> ({@code POST {base-url}/embeddings},
 * body {@code {model, input:[...]}}) dédié à la recherche sémantique du {@code recall}
 * (F-162 / SF-162-06).
 *
 * <p><b>Provider Independence</b> : on réutilise l'interface {@link EmbeddingProvider} déjà présente
 * (RAG documentaire), sans jamais dépendre d'un SDK Anthropic ni du RAG lui-même. Le domaine
 * ({@link AtelierSemanticRecallService}) ne dépend que de l'interface. Seule la <b>configuration</b> est
 * propre au recall ({@link RecallSemanticProperties}, clé {@code APP_EMBEDDING_API_KEY}), pour un
 * coupe-circuit indépendant de celui du RAG.</p>
 *
 * <p><b>Dormant par défaut.</b> Sans clé, {@link RecallSemanticProperties#isConfigured()} est faux et
 * chaque appel lève {@link EmbeddingProviderUnavailableException} <b>sans aucun appel réseau</b> — rien
 * ne part, et {@code recall} retombe sur le mot-clé. La clé n'est <b>jamais</b> journalisée : seul le
 * modèle apparaît dans les logs d'échec (patron STT / image / RAG).</p>
 *
 * <p><b>Pas de {@code @Component}</b> : le bean est déclaré par {@link AtelierSemanticRecallConfig} avec
 * {@code defaultCandidate=false} et injecté par {@code @Qualifier("recallEmbeddingProvider")}. Ainsi ce
 * second {@link EmbeddingProvider} ne perturbe pas l'injection par type du RAG documentaire (qui n'a
 * qu'un bean actif à la fois), tout en restant injectable là où il est explicitement qualifié.</p>
 */
public class OpenAiRecallEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiRecallEmbeddingProvider.class);

    private final RecallSemanticProperties properties;
    private final RestClient restClient;

    public OpenAiRecallEmbeddingProvider(RecallSemanticProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties))
                .build();
    }

    private static ClientHttpRequestFactory requestFactory(RecallSemanticProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) Math.min(Integer.MAX_VALUE, properties.timeout().toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }

    @Override
    public int dimension() {
        return properties.dimension();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (!properties.isConfigured()) {
            // Aucune clé => fournisseur dormant. On ne journalise jamais la clé (ici, elle est absente).
            throw new EmbeddingProviderUnavailableException(
                    "La recherche sémantique du recall n'est pas configurée.");
        }
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = Map.of("model", properties.model(), "input", texts);
        try {
            EmbeddingApiResponse response = restClient.post()
                    .uri("/embeddings")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(EmbeddingApiResponse.class);
            return toVectors(response, texts.size());
        } catch (RestClientException ex) {
            // Message neutre : ni la clé, ni la réponse brute du fournisseur ne remontent.
            log.warn("Appel au fournisseur d'embeddings (recall) en échec (modèle={})", properties.model());
            throw new EmbeddingProviderException("Échec de l'appel au fournisseur d'embeddings du recall.", ex);
        }
    }

    private static List<float[]> toVectors(EmbeddingApiResponse response, int expected) {
        if (response == null || response.data() == null || response.data().size() != expected) {
            throw new EmbeddingProviderException("Réponse d'embeddings du recall invalide.");
        }
        return response.data().stream()
                .sorted(java.util.Comparator.comparingInt(d -> d.index() == null ? 0 : d.index()))
                .map(EmbeddingApiResponse.Item::embedding)
                .map(OpenAiRecallEmbeddingProvider::toFloatArray)
                .toList();
    }

    private static float[] toFloatArray(List<Double> values) {
        if (values == null) {
            throw new EmbeddingProviderException("Vecteur d'embedding du recall manquant.");
        }
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i).floatValue();
        }
        return vector;
    }
}
