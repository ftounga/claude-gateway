package fr.claudegateway.atelier.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import fr.claudegateway.rag.provider.EmbeddingProviderUnavailableException;

/**
 * Le fournisseur d'embeddings du recall (F-162 / SF-162-06). On y fige le contrat <b>dormant</b> : sans
 * clé, aucun appel réseau n'est émis et l'appel lève {@link EmbeddingProviderUnavailableException}. Le
 * chemin réseau réel (OpenAI) est à valider en prod.
 */
class OpenAiRecallEmbeddingProviderTest {

    private OpenAiRecallEmbeddingProvider provider(String apiKey) {
        RecallSemanticProperties props = new RecallSemanticProperties(
                true, apiKey, null, null, 1536, null, null, null);
        return new OpenAiRecallEmbeddingProvider(props, RestClient.builder());
    }

    @Test
    @DisplayName("dimension : reflète la configuration")
    void dimensionFromConfig() {
        assertThat(provider("sk-key").dimension()).isEqualTo(1536);
    }

    @Test
    @DisplayName("non configuré (pas de clé) : lève Unavailable, AUCUN appel réseau")
    void unavailableWithoutKey() {
        OpenAiRecallEmbeddingProvider provider = provider(null);

        assertThatThrownBy(() -> provider.embed(List.of("texte")))
                .isInstanceOf(EmbeddingProviderUnavailableException.class);
    }
}
