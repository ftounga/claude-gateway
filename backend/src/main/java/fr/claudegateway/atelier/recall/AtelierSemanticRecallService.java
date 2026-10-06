package fr.claudegateway.atelier.recall;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import fr.claudegateway.rag.provider.EmbeddingProvider;

/**
 * Implémentation de la {@link AtelierSemanticRecall} (F-162 / SF-162-06) : elle embed les messages via un
 * {@link EmbeddingProvider} abstrait et range/recherche par {@link AtelierMessageEmbeddingStore} (pgvector).
 *
 * <p><b>Best-effort partout</b> : l'embedding à l'écriture part dans un pool dédié une fois la réponse
 * rendue et n'échoue jamais le tour ; la recherche renvoie une liste vide sur toute défaillance, ce qui
 * fait retomber {@code recall} sur le mot-clé. <b>Éteint sans clé</b> ({@link RecallSemanticProperties#isConfigured()}).</p>
 *
 * <p><b>Isolation</b> : {@link #search} passe au store le {@code user_id} et le {@code workspace_id} du
 * tour, filtrés dans le SQL vecteur.</p>
 */
@Service
public class AtelierSemanticRecallService implements AtelierSemanticRecall {

    private static final Logger log = LoggerFactory.getLogger(AtelierSemanticRecallService.class);

    private final EmbeddingProvider embeddingProvider;
    private final AtelierMessageEmbeddingStore store;
    private final RecallSemanticProperties properties;
    private final Executor executor;

    public AtelierSemanticRecallService(
            @Qualifier("recallEmbeddingProvider") EmbeddingProvider embeddingProvider,
            AtelierMessageEmbeddingStore store, RecallSemanticProperties properties,
            @Qualifier("atelierEmbeddingExecutor") Executor executor) {
        this.embeddingProvider = embeddingProvider;
        this.store = store;
        this.properties = properties;
        this.executor = executor;
    }

    @Override
    public boolean isEnabled() {
        return properties.isConfigured();
    }

    @Override
    public void embedAsync(UUID messageId, String content) {
        if (!isEnabled() || messageId == null || content == null || content.isBlank()) {
            return;
        }
        String text = truncate(content);
        try {
            executor.execute(() -> {
                try {
                    List<float[]> vectors = embeddingProvider.embed(List.of(text));
                    if (!vectors.isEmpty()) {
                        store.store(messageId, vectors.get(0));
                    }
                } catch (RuntimeException ex) {
                    // Best-effort : le message reste sans embedding, le backfill le rattrapera.
                    log.debug("Embedding de message non calculé ({})", ex.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException ex) {
            log.debug("Embedding de message non planifié ({})", ex.getClass().getSimpleName());
        }
    }

    @Override
    public List<UUID> search(UUID userId, UUID workspaceId, String query, int topN) {
        if (!isEnabled() || userId == null || workspaceId == null || query == null || query.isBlank()
                || topN <= 0) {
            return List.of();
        }
        try {
            List<float[]> vectors = embeddingProvider.embed(List.of(truncate(query)));
            if (vectors.isEmpty()) {
                return List.of();
            }
            return store.searchSimilar(userId, workspaceId, vectors.get(0), topN);
        } catch (RuntimeException ex) {
            // Toute défaillance sémantique => repli mot-clé côté appelant.
            log.debug("Recherche sémantique indisponible, repli mot-clé ({})", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public List<UUID> searchAcross(UUID userId, java.util.Collection<UUID> workspaceIds, String query,
            int topN) {
        if (!isEnabled() || userId == null || workspaceIds == null || workspaceIds.isEmpty()
                || query == null || query.isBlank() || topN <= 0) {
            return List.of();
        }
        try {
            List<float[]> vectors = embeddingProvider.embed(List.of(truncate(query)));
            if (vectors.isEmpty()) {
                return List.of();
            }
            return store.searchSimilarAcross(userId, workspaceIds, vectors.get(0), topN);
        } catch (RuntimeException ex) {
            log.debug("Recherche sémantique (poste) indisponible, repli mot-clé ({})",
                    ex.getClass().getSimpleName());
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
