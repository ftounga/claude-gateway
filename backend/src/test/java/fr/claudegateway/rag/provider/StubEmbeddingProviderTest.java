package fr.claudegateway.rag.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires du fournisseur d'embeddings stub (F-06 / SF-06-01) : dimension et déterminisme.
 */
class StubEmbeddingProviderTest {

    private StubEmbeddingProvider providerWithDimension(int dimension) {
        return new StubEmbeddingProvider(
                new EmbeddingProperties("stub", dimension, null, null, null, null));
    }

    @Test
    void producesVectorsOfConfiguredDimension() {
        StubEmbeddingProvider provider = providerWithDimension(1536);
        List<float[]> vectors = provider.embed(List.of("un", "deux"));
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).hasSize(1536);
        assertThat(vectors.get(1)).hasSize(1536);
        assertThat(provider.dimension()).isEqualTo(1536);
    }

    @Test
    void isDeterministicForEqualTexts() {
        StubEmbeddingProvider provider = providerWithDimension(64);
        float[] a = provider.embed(List.of("même texte")).get(0);
        float[] b = provider.embed(List.of("même texte")).get(0);
        assertThat(a).containsExactly(b);
    }

    @Test
    void vectorIsNormalized() {
        StubEmbeddingProvider provider = providerWithDimension(128);
        float[] v = provider.embed(List.of("norme")).get(0);
        double norm = 0.0;
        for (float x : v) {
            norm += (double) x * x;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }
}
