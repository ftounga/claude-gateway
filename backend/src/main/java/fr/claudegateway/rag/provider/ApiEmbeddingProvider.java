package fr.claudegateway.rag.provider;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Implémentation {@link EmbeddingProvider} pour une API d'embeddings externe au format
 * <b>OpenAI-compatible</b> ({@code POST /embeddings}, body {@code {model, input:[...]}}). Active
 * uniquement sur {@code app.rag.embedding.provider=api}.
 *
 * <p>Seul point du code couplé au fournisseur : le domaine ne dépend que de l'interface
 * {@link EmbeddingProvider}. La clé est lue depuis {@link EmbeddingProperties} (environnement
 * uniquement) et n'est <b>jamais</b> journalisée ni renvoyée. Si elle est absente, chaque appel lève
 * {@link EmbeddingProviderUnavailableException}. Toute erreur amont est traduite en
 * {@link EmbeddingProviderException} avec un message neutre (ni clé, ni réponse brute).</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.rag.embedding", name = "provider", havingValue = "api")
public class ApiEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(ApiEmbeddingProvider.class);

    private final EmbeddingProperties properties;
    private final RestClient restClient;

    public ApiEmbeddingProvider(EmbeddingProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties))
                .build();
    }

    private static ClientHttpRequestFactory requestFactory(EmbeddingProperties properties) {
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
            throw new EmbeddingProviderUnavailableException("Le fournisseur d'embeddings n'est pas configuré.");
        }
        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "input", texts);
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
            // Message métier neutre : ni la clé, ni la réponse brute du fournisseur ne remontent.
            log.warn("Appel au fournisseur d'embeddings en échec (modèle={})", properties.model());
            throw new EmbeddingProviderException("Échec de l'appel au fournisseur d'embeddings.", ex);
        }
    }

    private List<float[]> toVectors(EmbeddingApiResponse response, int expected) {
        if (response == null || response.data() == null || response.data().size() != expected) {
            throw new EmbeddingProviderException("Réponse d'embeddings invalide.");
        }
        return response.data().stream()
                .sorted(java.util.Comparator.comparingInt(d -> d.index() == null ? 0 : d.index()))
                .map(EmbeddingApiResponse.Item::embedding)
                .map(ApiEmbeddingProvider::toFloatArray)
                .toList();
    }

    private static float[] toFloatArray(List<Double> values) {
        if (values == null) {
            throw new EmbeddingProviderException("Vecteur d'embedding manquant.");
        }
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i).floatValue();
        }
        return vector;
    }
}
