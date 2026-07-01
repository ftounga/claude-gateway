package fr.claudegateway.ask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ChatMessage;
import fr.claudegateway.ai.ChatRole;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.ask.dto.CitationResponse;
import fr.claudegateway.chat.UnsupportedModelException;
import fr.claudegateway.ocr.Document;
import fr.claudegateway.ocr.DocumentRepository;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.rag.Chunk;
import fr.claudegateway.rag.ChunkRepository;
import fr.claudegateway.rag.provider.EmbeddingProvider;
import fr.claudegateway.rag.store.EmbeddingStore;
import fr.claudegateway.rag.store.ScoredChunk;

/**
 * Cœur du Q&A documentaire (F-07 / SF-07-01). Répond à une question ancrée sur les documents indexés
 * de l'utilisateur : vectorisation de la question, recherche top-K pgvector <b>isolée
 * {@code user_id}</b>, construction d'un prompt cité {@code [filename:page:chunkIndex]}, puis relais
 * vers Claude via l'abstraction {@link AIProvider} (jamais un SDK direct — Gateway-First / Provider
 * Independence).
 *
 * <p>Le quota est vérifié <b>avant</b> tout appel fournisseur (F-10) et la consommation enregistrée
 * après. En l'absence de chunk pertinent (aucun document indexé, store no-op, aucune correspondance),
 * la réponse est fournie <b>sans contexte</b> et marquée {@code grounded=false} (repli gracieux).
 * Aucun secret ni contenu brut fournisseur n'est journalisé ; les erreurs restent neutres.</p>
 */
@Service
public class AskService {

    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingStore embeddingStore;
    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final AIProvider aiProvider;
    private final ModelCatalog modelCatalog;
    private final QuotaService quotaService;
    private final AskProperties askProperties;

    public AskService(
            EmbeddingProvider embeddingProvider,
            EmbeddingStore embeddingStore,
            ChunkRepository chunkRepository,
            DocumentRepository documentRepository,
            AIProvider aiProvider,
            ModelCatalog modelCatalog,
            QuotaService quotaService,
            AskProperties askProperties) {
        this.embeddingProvider = embeddingProvider;
        this.embeddingStore = embeddingStore;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.aiProvider = aiProvider;
        this.modelCatalog = modelCatalog;
        this.quotaService = quotaService;
        this.askProperties = askProperties;
    }

    /**
     * Traite une question documentaire pour l'utilisateur courant.
     *
     * @param userId          utilisateur authentifié (contexte de sécurité — porte l'isolation)
     * @param rawQuestion     question (déjà validée non vide côté controller)
     * @param requestedModel  modèle souhaité ou null (défaut sinon)
     * @param requestedTopK   nombre de chunks de contexte souhaité ou null (défaut/borné sinon)
     * @return la réponse citée (ou en repli si aucun contexte pertinent)
     * @throws UnsupportedModelException si le modèle demandé n'est pas dans la liste blanche
     */
    public AskResult ask(UUID userId, String rawQuestion, String requestedModel, Integer requestedTopK) {
        String question = rawQuestion.trim();
        // Contrôle de quota AVANT toute vectorisation / appel fournisseur (F-10) : à quota atteint,
        // 402 sans aucun appel réseau.
        quotaService.assertWithinQuota(userId);
        String model = resolveModel(requestedModel);
        int topK = askProperties.clampTopK(requestedTopK);

        // Vectorisation de la question via l'abstraction fournisseur (jamais un SDK direct).
        float[] queryVector = embeddingProvider.embed(List.of(question)).get(0);

        // Recherche top-K isolée user_id, puis rechargement des chunks filtré user_id (défense en
        // profondeur) et ré-ordonnancement selon la pertinence.
        List<Chunk> contextChunks = retrieveContext(userId, queryVector, topK);

        boolean grounded = !contextChunks.isEmpty();
        Map<UUID, Document> documents = grounded ? loadDocuments(userId, contextChunks) : Map.of();

        String prompt = grounded
                ? buildGroundedPrompt(question, contextChunks, documents)
                : buildUngroundedPrompt(question);

        ChatCompletionResult completion = aiProvider.complete(new ChatCompletionRequest(
                model, List.of(new ChatMessage(ChatRole.USER, prompt))));

        // Enregistre la consommation de tokens (F-10).
        quotaService.recordUsage(userId, completion.inputTokens(), completion.outputTokens());

        List<CitationResponse> citations = grounded
                ? buildCitations(contextChunks, documents)
                : List.of();
        return new AskResult(completion.content(), completion.model(), grounded, citations);
    }

    /** Recherche vectorielle isolée puis rechargement ordonné par pertinence (isolation user_id). */
    private List<Chunk> retrieveContext(UUID userId, float[] queryVector, int topK) {
        List<ScoredChunk> hits = embeddingStore.search(userId, queryVector, topK);
        if (hits.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = hits.stream().map(ScoredChunk::chunkId).toList();
        // Rechargement filtré user_id : un chunk d'un autre utilisateur ne peut jamais être chargé.
        Map<UUID, Chunk> byId = new LinkedHashMap<>();
        for (Chunk chunk : chunkRepository.findByIdInAndUserId(ids, userId)) {
            byId.put(chunk.getId(), chunk);
        }
        // Conserve l'ordre de pertinence retourné par la recherche.
        List<Chunk> ordered = new ArrayList<>(byId.size());
        for (ScoredChunk hit : hits) {
            Chunk chunk = byId.get(hit.chunkId());
            if (chunk != null) {
                ordered.add(chunk);
            }
        }
        return ordered;
    }

    /** Charge les documents source des chunks, filtrés user_id (isolation), indexés par id. */
    private Map<UUID, Document> loadDocuments(UUID userId, List<Chunk> chunks) {
        Map<UUID, Document> documents = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            UUID documentId = chunk.getDocumentId();
            if (!documents.containsKey(documentId)) {
                documentRepository.findByIdAndUserId(documentId, userId)
                        .ifPresent(doc -> documents.put(documentId, doc));
            }
        }
        return documents;
    }

    private String resolveModel(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            return modelCatalog.defaultModel();
        }
        if (!modelCatalog.supports(requestedModel)) {
            throw new UnsupportedModelException("Modèle non supporté : " + requestedModel);
        }
        return requestedModel;
    }

    /**
     * Construit le prompt cité : consigne d'ancrage + contexte numéroté portant les marqueurs
     * {@code [filename:page:chunkIndex]} + question. Le modèle est invité à citer ses sources.
     */
    private String buildGroundedPrompt(String question, List<Chunk> chunks, Map<UUID, Document> documents) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tu es un assistant qui répond à des questions en t'appuyant EXCLUSIVEMENT sur les ")
                .append("extraits de documents fournis ci-dessous. Cite systématiquement tes sources ")
                .append("avec le marqueur exact indiqué devant chaque extrait, au format ")
                .append("[fichier:page:chunk]. Si les extraits ne permettent pas de répondre, dis-le ")
                .append("clairement.\n\n");
        sb.append("=== EXTRAITS ===\n");
        for (Chunk chunk : chunks) {
            String citation = citationMarker(chunk, documents.get(chunk.getDocumentId()));
            sb.append(citation).append('\n').append(chunk.getText()).append("\n\n");
        }
        sb.append("=== QUESTION ===\n").append(question);
        return sb.toString();
    }

    /** Prompt de repli : question posée sans contexte documentaire (réponse non ancrée). */
    private String buildUngroundedPrompt(String question) {
        return "Aucun extrait de document indexé n'est disponible pour cette question. "
                + "Réponds au mieux de tes connaissances générales, en précisant que ta réponse "
                + "n'est pas fondée sur les documents de l'utilisateur.\n\n"
                + "=== QUESTION ===\n" + question;
    }

    private List<CitationResponse> buildCitations(List<Chunk> chunks, Map<UUID, Document> documents) {
        List<CitationResponse> citations = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            Document document = documents.get(chunk.getDocumentId());
            citations.add(new CitationResponse(
                    chunk.getDocumentId(),
                    document != null ? document.getFilename() : "(document supprimé)",
                    chunk.getPageNumber(),
                    chunk.getChunkIndex(),
                    snippet(chunk.getText())));
        }
        return citations;
    }

    /** Marqueur de citation {@code [filename:page:chunkIndex]} (page omise si non dérivée). */
    private String citationMarker(Chunk chunk, Document document) {
        String filename = document != null ? document.getFilename() : "document";
        String page = chunk.getPageNumber() != null ? String.valueOf(chunk.getPageNumber()) : "-";
        return "[" + filename + ":" + page + ":" + chunk.getChunkIndex() + "]";
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.strip();
        int max = askProperties.snippetMaxChars();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max).stripTrailing() + "…";
    }

    /** Résultat interne d'une question documentaire. */
    public record AskResult(String answer, String model, boolean grounded, List<CitationResponse> citations) {
    }
}
