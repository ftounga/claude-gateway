package fr.claudegateway.atelier.actions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.recall.RecallSemanticProperties;
import fr.claudegateway.rag.provider.EmbeddingProvider;

/**
 * <b>Déjà demandé ?</b> — le dédoublonnage <b>par le sens</b> des attentes (F-175 / SF-175-03,
 * décision D3).
 *
 * <p>La clé normalisée reconnaît la même demande dite deux fois pareil ; elle rate « compte forge
 * CAPFM » contre « accès à la forge pour CAPFM » — c'est exactement le doublon vu en prod (deux
 * terminaux, le même jour). Ici, la description entrante est vectorisée par le <b>fournisseur
 * abstrait</b> d'embeddings de F-162 / F-174, comparée aux attentes <b>ouvertes du poste</b>, et
 * reconnue sous un <b>seuil exigeant</b> (silencieux &gt; bruyant : une fausse fusion cacherait une
 * vraie demande).</p>
 *
 * <p><b>Éteint sans clé</b> ({@link RecallSemanticProperties#isConfigured()}) : la clé normalisée
 * reste seule juge. Toute défaillance rend « aucun doublon » — on inscrit plutôt que de perdre une
 * demande. Les attentes encore sans vecteur du périmètre sont rattrapées (bornées) au moment de la
 * comparaison.</p>
 *
 * <p><b>Provider Independence</b> : aucun SDK ici ; le vecteur vient de {@code recallEmbeddingProvider}.</p>
 */
@Service
public class TerminalActionSemanticDedup {

    private static final Logger log = LoggerFactory.getLogger(TerminalActionSemanticDedup.class);

    /** Combien d'attentes sans vecteur on rattrape au plus par comparaison. */
    static final int CATCH_UP_LIMIT = 50;

    private final EmbeddingProvider embeddingProvider;
    private final TerminalActionEmbeddingStore store;
    private final RecallSemanticProperties properties;
    private final Executor executor;
    private final double maxDistance;

    public TerminalActionSemanticDedup(
            @Qualifier("recallEmbeddingProvider") EmbeddingProvider embeddingProvider,
            TerminalActionEmbeddingStore store,
            RecallSemanticProperties properties,
            @Qualifier("atelierEmbeddingExecutor") Executor executor,
            @Value("${app.atelier.actions.dedup-max-distance:0.20}") double maxDistance) {
        this.embeddingProvider = embeddingProvider;
        this.store = store;
        this.properties = properties;
        this.executor = executor;
        this.maxDistance = maxDistance;
    }

    /** Vrai si le chemin sémantique est appelable (coupe-circuit + clé). */
    public boolean isEnabled() {
        return properties != null && properties.isConfigured();
    }

    /**
     * L'attente ouverte du périmètre qui dit <b>la même chose</b>, s'il y en a une sous le seuil.
     *
     * @param hostId      poste du terminal du tour ; {@code null} pour un terminal hébergé
     * @param workspaceId terminal du tour (périmètre quand il n'a pas de poste)
     */
    public Optional<Match> findSimilarOpen(UUID userId, UUID hostId, UUID workspaceId, String description) {
        if (!isEnabled() || userId == null || description == null || description.isBlank()) {
            return Optional.empty();
        }
        try {
            catchUp(userId, hostId, workspaceId);
            List<float[]> vectors = embeddingProvider.embed(List.of(truncate(description)));
            if (vectors.isEmpty()) {
                return Optional.empty();
            }
            return store.searchOpen(userId, hostId, workspaceId, vectors.get(0), 1).stream()
                    .filter(s -> s.distance() <= maxDistance)
                    .findFirst()
                    .map(s -> new Match(s.id(), s.distance()));
        } catch (RuntimeException ex) {
            log.debug("Dédoublonnage par le sens indisponible ({})", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Planifie l'embedding d'une attente fraîchement inscrite. Best-effort, jamais bloquant. */
    public void embedAsync(UUID actionId, String description) {
        if (!isEnabled() || actionId == null || description == null || description.isBlank()) {
            return;
        }
        String text = truncate(description);
        try {
            executor.execute(() -> {
                try {
                    List<float[]> vectors = embeddingProvider.embed(List.of(text));
                    if (!vectors.isEmpty()) {
                        store.store(actionId, vectors.get(0));
                    }
                } catch (RuntimeException ex) {
                    log.debug("Embedding d'attente non calculé ({})", ex.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException ex) {
            log.debug("Embedding d'attente non planifié ({})", ex.getClass().getSimpleName());
        }
    }

    /** Rattrape (en un seul appel) les attentes ouvertes du périmètre restées sans vecteur. */
    private void catchUp(UUID userId, UUID hostId, UUID workspaceId) {
        List<TerminalActionEmbeddingStore.Unembedded> missing =
                store.findOpenUnembedded(userId, hostId, workspaceId, CATCH_UP_LIMIT);
        if (missing.isEmpty()) {
            return;
        }
        List<String> texts = new ArrayList<>(missing.size());
        for (TerminalActionEmbeddingStore.Unembedded row : missing) {
            texts.add(truncate(row.description()));
        }
        List<float[]> vectors = embeddingProvider.embed(texts);
        for (int i = 0; i < Math.min(vectors.size(), missing.size()); i++) {
            store.store(missing.get(i).id(), vectors.get(i));
        }
    }

    private static String truncate(String text) {
        String flat = text.strip();
        return flat.length() > RecallSemanticProperties.MAX_EMBED_CHARS
                ? flat.substring(0, RecallSemanticProperties.MAX_EMBED_CHARS)
                : flat;
    }

    /** Une attente reconnue par le sens, et sa distance cosine. */
    public record Match(UUID actionId, double distance) {
    }
}
