package fr.claudegateway.atelier.resolution;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.recall.RecallSemanticProperties;
import fr.claudegateway.atelier.resolution.ResolutionMemoryEmbeddingStore.ScoredResolution;
import fr.claudegateway.rag.provider.EmbeddingProvider;

/**
 * Le <b>rappel sémantique</b> de la mémoire de résolutions (F-148 / SF-148-11) : il embed la
 * <b>question</b> via un {@link EmbeddingProvider} abstrait et range/recherche par
 * {@link ResolutionMemoryEmbeddingStore} (pgvector). On apparie par la question, pas la conclusion.
 *
 * <p><b>Mêmes garanties que {@code AtelierSemanticRecallService}</b> (SF-162-06), qu'il réutilise comme
 * patron : l'embedding à l'enregistrement part dans le pool {@code atelierEmbeddingExecutor} et n'échoue
 * jamais le tour ; la recherche renvoie une liste vide sur toute défaillance, ce qui fait retomber le
 * rappel sur le Jaccard lexical de SF-148-08. <b>Éteint sans clé</b>
 * ({@link RecallSemanticProperties#isConfigured()}) : comportement strictement identique à SF-148-08.</p>
 *
 * <p><b>Isolation</b> : {@link #searchSimilarQuestions} passe {@code user_id} ET {@code host_id} au store,
 * filtrés dans le SQL vecteur (la mémoire est par poste).</p>
 *
 * <p><b>Provider Independence / Gateway-First</b> : aucune capacité IA n'est réimplémentée ; le vecteur
 * vient du fournisseur abstrait {@code recallEmbeddingProvider}. Le store ne fait que ranger/chercher.</p>
 */
@Service
public class ResolutionSemanticRecall {

    private static final Logger log = LoggerFactory.getLogger(ResolutionSemanticRecall.class);

    private final EmbeddingProvider embeddingProvider;
    private final ResolutionMemoryEmbeddingStore store;
    private final RecallSemanticProperties properties;
    private final Executor executor;

    /**
     * Seuil de distance cosine <b>exigeant</b> (anti-faux-positif). Une résolution n'est proposée que si la
     * distance à la question entrante est ≤ ce seuil ; au-delà, repli Jaccard (silencieux &gt; bruyant).
     * Défaut {@code 0.30} (≈ similarité cosine 0.70), réglable par env sans redéploiement.
     */
    private final double maxDistance;

    public ResolutionSemanticRecall(
            @Qualifier("recallEmbeddingProvider") EmbeddingProvider embeddingProvider,
            ResolutionMemoryEmbeddingStore store, RecallSemanticProperties properties,
            @Qualifier("atelierEmbeddingExecutor") Executor executor,
            @Value("${app.atelier.recall.semantic.resolution-max-distance:0.30}") double maxDistance) {
        this.embeddingProvider = embeddingProvider;
        this.store = store;
        this.properties = properties;
        this.executor = executor;
        this.maxDistance = maxDistance;
    }

    /** Vrai si le sémantique est réellement appelable (coupe-circuit + clé). Faux ⇒ repli Jaccard. */
    public boolean isEnabled() {
        return properties.isConfigured();
    }

    /** Seuil de distance cosine au-delà duquel on ne propose rien (repli Jaccard). */
    public double maxDistance() {
        return maxDistance;
    }

    /** Nombre de voisins candidats demandés au store (le premier sous le seuil l'emporte). */
    public int topN() {
        return properties.topN();
    }

    /**
     * Planifie, hors du chemin critique, l'embedding de la <b>question</b> d'une résolution fraîchement
     * enregistrée. Best-effort : un échec laisse la ligne sans vecteur (le backfill la rattrapera) et ne
     * remonte jamais. No-op si le sémantique est éteint ou l'entrée vide.
     */
    public void embedQuestionAsync(UUID resolutionId, String question) {
        if (!isEnabled() || resolutionId == null || question == null || question.isBlank()) {
            return;
        }
        String text = truncate(question);
        try {
            executor.execute(() -> {
                try {
                    List<float[]> vectors = embeddingProvider.embed(List.of(text));
                    if (!vectors.isEmpty()) {
                        store.store(resolutionId, vectors.get(0));
                    }
                } catch (RuntimeException ex) {
                    // Best-effort : la résolution reste sans embedding, le backfill (SF-148-12) la rattrapera.
                    log.debug("Embedding de résolution non calculé ({})", ex.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException ex) {
            log.debug("Embedding de résolution non planifié ({})", ex.getClass().getSimpleName());
        }
    }

    /**
     * Les résolutions les plus proches de la question entrante, du plus proche au plus lointain (id +
     * distance cosine), isolées {@code (user_id, host_id)}. Liste vide si éteint, requête vide, ou sur toute
     * défaillance (⇒ repli Jaccard côté appelant). Le seuil {@link #maxDistance()} est appliqué par
     * l'appelant.
     */
    public List<ScoredResolution> searchSimilarQuestions(UUID userId, UUID hostId, String question, int topN) {
        if (!isEnabled() || userId == null || hostId == null || question == null || question.isBlank()
                || topN <= 0) {
            return List.of();
        }
        try {
            List<float[]> vectors = embeddingProvider.embed(List.of(truncate(question)));
            if (vectors.isEmpty()) {
                return List.of();
            }
            return store.searchSimilarQuestions(userId, hostId, vectors.get(0), topN);
        } catch (RuntimeException ex) {
            // Toute défaillance sémantique => repli Jaccard côté appelant.
            log.debug("Rappel sémantique indisponible, repli Jaccard ({})", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private static String truncate(String text) {
        String flat = text.strip();
        return flat.length() > RecallSemanticProperties.MAX_EMBED_CHARS
                ? flat.substring(0, RecallSemanticProperties.MAX_EMBED_CHARS)
                : flat;
    }
}
