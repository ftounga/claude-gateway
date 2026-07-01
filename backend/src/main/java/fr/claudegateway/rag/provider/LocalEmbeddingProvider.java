package fr.claudegateway.rag.provider;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fournisseur d'embeddings <b>local, in-process</b> (F-15 / SF-15-01). Génère les vecteurs
 * <b>sans réseau, sans clé API et sans SDK fournisseur</b> — c'est l'objectif de F-15 : « embeddings
 * locaux, suppression de la dépendance fournisseur ». Actif uniquement sur
 * {@code app.rag.embedding.provider=local} (mutuellement exclusif avec {@code stub} / {@code api}).
 *
 * <p><b>Technique</b> : vectoriseur lexical par <i>hashing trick</i> signé. Chaque texte est
 * normalisé (minuscules) et tokenisé en mots ; pour chaque mot on émet le mot entier <b>et</b> ses
 * n-grammes de caractères ({@value #CHAR_NGRAM}) afin de capter la morphologie. Chaque feature est
 * projetée dans {@code [0, dimension)} via un hachage stable (FNV-1a 32-bit), avec un signe ±1 dérivé
 * du même hachage (hashing signé, réduit le biais de collision) ; la fréquence de terme (TF) est
 * accumulée, puis le vecteur est normalisé (norme L2 = 1). Deux textes partageant du vocabulaire sont
 * donc plus proches (distance L2 plus faible) que deux textes disjoints — sémantique lexicale réelle,
 * contrairement au {@code stub} (aucune proximité inter-textes).</p>
 *
 * <p><b>Provider Independence</b> : le domaine ({@code IngestionService}, {@code AskService}) ne
 * dépend que de {@link EmbeddingProvider}. Un backend transformer (all-MiniLM ONNX, 384-dim) pourra
 * remplacer cette implémentation sur la même interface sans réécriture du domaine. La dimension reste
 * pilotée par {@code app.rag.embedding.dimension} (défaut 1536, OQ-01), compatible avec la colonne
 * {@code chunks.embedding vector(1536)} existante sans migration.</p>
 *
 * <p>Déterministe (aucun aléa), sans état, thread-safe : aucun secret n'est manipulé ni journalisé.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.rag.embedding", name = "provider", havingValue = "local")
public class LocalEmbeddingProvider implements EmbeddingProvider {

    /** Taille des n-grammes de caractères extraits de chaque mot (robustesse morphologique). */
    static final int CHAR_NGRAM = 3;

    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    private final int dimension;

    public LocalEmbeddingProvider(EmbeddingProperties properties) {
        // EmbeddingProperties garantit déjà une dimension > 0 (défaut 1536).
        this.dimension = properties.dimension();
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(vectorize(text));
        }
        return vectors;
    }

    /**
     * Transforme un texte en vecteur normalisé de dimension {@link #dimension}. Un texte vide/blanc/
     * {@code null} donne un vecteur zéro (aucune feature, norme nulle → pas de normalisation).
     */
    private float[] vectorize(String text) {
        float[] vector = new float[dimension];
        if (text == null || text.isBlank()) {
            return vector; // vecteur zéro, dimension respectée.
        }
        for (String token : tokenize(text)) {
            accumulate(vector, token);
            for (String ngram : charNgrams(token)) {
                accumulate(vector, ngram);
            }
        }
        normalizeL2(vector);
        return vector;
    }

    /** Accumule une feature dans le vecteur : bucket = |hash| % dim, signe = bit de poids fort du hash. */
    private void accumulate(float[] vector, String feature) {
        int hash = fnv1a(feature);
        int bucket = Math.floorMod(hash, dimension);
        float sign = (hash & 0x80000000) == 0 ? 1.0f : -1.0f;
        vector[bucket] += sign;
    }

    /** Tokenisation lexicale : minuscules (Locale.ROOT) + découpage sur tout non alphanumérique Unicode. */
    private static List<String> tokenize(String text) {
        String[] raw = text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        List<String> tokens = new ArrayList<>(raw.length);
        for (String t : raw) {
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /** N-grammes de caractères d'un mot ({@value #CHAR_NGRAM}) ; vide si le mot est plus court. */
    private static List<String> charNgrams(String token) {
        if (token.length() < CHAR_NGRAM) {
            return List.of();
        }
        List<String> ngrams = new ArrayList<>(token.length() - CHAR_NGRAM + 1);
        for (int i = 0; i + CHAR_NGRAM <= token.length(); i++) {
            ngrams.add(token.substring(i, i + CHAR_NGRAM));
        }
        return ngrams;
    }

    /** Hachage FNV-1a 32-bit : stable, portable (indépendant de {@code String.hashCode}). */
    private static int fnv1a(String value) {
        int hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    /** Normalise le vecteur à une norme L2 = 1 (no-op si vecteur nul). */
    private static void normalizeL2(float[] vector) {
        double norm = 0.0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
    }
}
