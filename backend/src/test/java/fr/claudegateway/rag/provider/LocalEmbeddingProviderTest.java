package fr.claudegateway.rag.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.assertj.core.data.Offset;

/**
 * Tests unitaires du fournisseur d'embeddings local (F-15 / SF-15-01) : dimension, déterminisme,
 * normalisation L2, gestion du texte vide, préservation de l'ordre et <b>similarité lexicale</b>
 * (deux textes partageant du vocabulaire sont plus proches que deux textes disjoints).
 */
class LocalEmbeddingProviderTest {

    private LocalEmbeddingProvider providerWithDimension(int dimension) {
        return new LocalEmbeddingProvider(
                new EmbeddingProperties("local", dimension, null, null, null, null));
    }

    @Test
    void producesVectorsOfConfiguredDimension() {
        LocalEmbeddingProvider provider = providerWithDimension(1536);
        List<float[]> vectors = provider.embed(List.of("premier texte", "second texte"));
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).hasSize(1536);
        assertThat(vectors.get(1)).hasSize(1536);
        assertThat(provider.dimension()).isEqualTo(1536);
    }

    @Test
    void isDeterministicForEqualTexts() {
        LocalEmbeddingProvider provider = providerWithDimension(256);
        float[] a = provider.embed(List.of("contrat de prestation")).get(0);
        float[] b = provider.embed(List.of("contrat de prestation")).get(0);
        assertThat(a).containsExactly(b);
    }

    @Test
    void vectorIsL2NormalizedForNonEmptyText() {
        LocalEmbeddingProvider provider = providerWithDimension(512);
        float[] v = provider.embed(List.of("obligations respectives des parties")).get(0);
        assertThat(l2Norm(v)).isCloseTo(1.0, Offset.offset(1e-4));
    }

    @Test
    void blankOrNullTextYieldsZeroVectorWithoutException() {
        LocalEmbeddingProvider provider = providerWithDimension(128);
        List<float[]> vectors = provider.embed(java.util.Arrays.asList("", "   ", null));
        assertThat(vectors).hasSize(3);
        assertThat(vectors).allSatisfy(v -> {
            assertThat(v).hasSize(128);
            assertThat(l2Norm(v)).isZero();
        });
    }

    @Test
    void emptyInputYieldsEmptyOutput() {
        LocalEmbeddingProvider provider = providerWithDimension(64);
        assertThat(provider.embed(List.of())).isEmpty();
        assertThat(provider.embed(null)).isEmpty();
    }

    @Test
    void preservesOrderAndBatchSize() {
        LocalEmbeddingProvider provider = providerWithDimension(64);
        // "alpha" seul vs le même dans un lot : le vecteur d'une position doit être stable.
        float[] single = provider.embed(List.of("alpha")).get(0);
        List<float[]> batch = provider.embed(List.of("beta", "alpha", "gamma"));
        assertThat(batch).hasSize(3);
        assertThat(batch.get(1)).containsExactly(single);
    }

    @Test
    void lexicallyRelatedTextsAreCloserThanDisjointTexts() {
        LocalEmbeddingProvider provider = providerWithDimension(1024);
        float[] base = provider.embed(
                List.of("le contrat definit les obligations des parties signataires")).get(0);
        float[] related = provider.embed(
                List.of("les obligations des parties au contrat sont definies")).get(0);
        float[] disjoint = provider.embed(
                List.of("recette de cuisine tomates basilic huile olive")).get(0);

        double distRelated = l2Distance(base, related);
        double distDisjoint = l2Distance(base, disjoint);
        assertThat(distRelated).isLessThan(distDisjoint);
    }

    private static double l2Norm(float[] v) {
        double sum = 0.0;
        for (float x : v) {
            sum += (double) x * x;
        }
        return Math.sqrt(sum);
    }

    private static double l2Distance(float[] a, float[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double d = (double) a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }
}
