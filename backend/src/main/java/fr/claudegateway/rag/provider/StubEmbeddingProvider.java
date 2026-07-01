package fr.claudegateway.rag.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Implémentation d'embeddings déterministe par défaut (dev, tests, environnements sans réseau).
 * Aucune dépendance externe : elle dérive un vecteur stable de la valeur de hachage du texte, ce qui
 * permet au pipeline d'ingestion complet de fonctionner sans clé API. Active tant que
 * {@code app.rag.embedding.provider} n'est pas {@code api}.
 *
 * <p>Les vecteurs sont normalisés (norme L2 = 1) et de la dimension configurée. Deux textes égaux
 * produisent le même vecteur ; ce n'est <b>pas</b> une sémantique réelle (aucune proximité de sens),
 * uniquement un substitut déterministe pour les tests et le développement local.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.rag.embedding", name = "provider", havingValue = "stub", matchIfMissing = true)
public class StubEmbeddingProvider implements EmbeddingProvider {

    private final int dimension;

    public StubEmbeddingProvider(EmbeddingProperties properties) {
        this.dimension = properties.dimension();
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(pseudoVector(text));
        }
        return vectors;
    }

    private float[] pseudoVector(String text) {
        Random random = new Random(text == null ? 0 : text.hashCode());
        float[] vector = new float[dimension];
        double norm = 0.0;
        for (int i = 0; i < dimension; i++) {
            float value = (float) (random.nextDouble() * 2.0 - 1.0);
            vector[i] = value;
            norm += (double) value * value;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }
}
