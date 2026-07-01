package fr.claudegateway.rag;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.ocr.Document;
import fr.claudegateway.ocr.DocumentRepository;
import fr.claudegateway.ocr.DocumentStatus;
import fr.claudegateway.rag.provider.EmbeddingProvider;
import fr.claudegateway.rag.provider.EmbeddingProviderException;
import fr.claudegateway.rag.provider.EmbeddingProviderUnavailableException;
import fr.claudegateway.rag.store.EmbeddingStore;

/**
 * Cœur de l'ingestion RAG (F-06 / SF-06-01) : transforme le texte OCR d'un document {@code EXTRACTED}
 * en chunks vectorisés persistés, puis fait passer le document à {@code INDEXED}.
 *
 * <p>Le domaine dépend uniquement des abstractions {@link EmbeddingProvider} (génération des vecteurs)
 * et {@link EmbeddingStore} (persistance vectorielle pgvector / no-op) — jamais d'un SDK fournisseur
 * ni du SQL vectoriel (Provider Independence). L'appel d'embeddings (réseau) est effectué hors de toute
 * transaction longue. La ré-ingestion est idempotente (suppression puis recréation des chunks, isolée
 * par {@code user_id}). Aucun secret ni contenu n'est journalisé ; les échecs fournisseur mènent à
 * {@code FAILED} avec un message neutre, jamais propagés au client.</p>
 *
 * <p>Ce service ne s'exécute jamais dans un thread HTTP : il est destiné à être invoqué par un worker
 * planifié (SF-06-02) — les traitements lourds restent asynchrones.</p>
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final Chunker chunker;
    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingStore embeddingStore;

    public IngestionService(
            DocumentRepository documentRepository,
            ChunkRepository chunkRepository,
            Chunker chunker,
            EmbeddingProvider embeddingProvider,
            EmbeddingStore embeddingStore) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.chunker = chunker;
        this.embeddingProvider = embeddingProvider;
        this.embeddingStore = embeddingStore;
    }

    /**
     * Auto-indexe les documents en attente (SF-06-02) : sélectionne les documents {@code EXTRACTED},
     * les réclame ({@code INDEXING}) puis les ingère. Un échec sur un document (capturé) n'interrompt
     * pas le lot. Exécuté hors thread HTTP (worker planifié). Aucun secret ni contenu journalisé.
     *
     * @return le nombre de documents passés à {@code INDEXED} ce cycle
     */
    public int ingestPending() {
        List<Document> pending = documentRepository.findByStatus(DocumentStatus.EXTRACTED);
        int indexed = 0;
        for (Document document : pending) {
            try {
                // Réclamation : rend l'état visible (badge INDEXING) et matérialise la prise en charge.
                document.setStatus(DocumentStatus.INDEXING);
                documentRepository.save(document);
                ingest(document);
                if (document.getStatus() == DocumentStatus.INDEXED) {
                    indexed++;
                }
            } catch (RuntimeException ex) {
                // Robustesse : un document en erreur inattendue ne bloque pas les suivants. Message neutre.
                log.warn("Ingestion RAG interrompue pour un document (id={}) — lot poursuivi", document.getId());
            }
        }
        return indexed;
    }

    /**
     * Ingère un document OCR extrait : découpe, vectorise, persiste les chunks et indexe.
     *
     * <p>Idempotent et sûr à ré-exécuter. N'agit que sur un document {@code EXTRACTED} ou déjà
     * {@code INDEXING} (repris) : tout autre statut est ignoré (retourne 0), ce qui évite un
     * double traitement ou l'ingestion d'un document non prêt.</p>
     *
     * @param document document à ingérer (son {@code user_id} porte l'isolation des chunks)
     * @return le nombre de chunks indexés (0 si texte vide, statut ignoré ou échec fournisseur)
     */
    public int ingest(Document document) {
        if (document.getStatus() != DocumentStatus.EXTRACTED
                && document.getStatus() != DocumentStatus.INDEXING) {
            return 0; // document non prêt / déjà traité : aucune action (idempotence).
        }

        List<ChunkText> chunkTexts = chunker.chunk(document.getExtractedText());
        if (chunkTexts.isEmpty()) {
            // Rien à indexer (texte vide/blanc) : document tout de même finalisé (0 chunk).
            chunkRepository.deleteByDocumentIdAndUserId(document.getId(), document.getUserId());
            finalizeIndexed(document, 0);
            return 0;
        }

        try {
            List<float[]> vectors = embeddingProvider.embed(
                    chunkTexts.stream().map(ChunkText::text).toList());
            if (vectors == null || vectors.size() != chunkTexts.size()) {
                throw new EmbeddingProviderException("Nombre de vecteurs incohérent avec les chunks.");
            }

            // Idempotence : on efface les chunks pré-existants du document (isolation user_id) avant recréation.
            chunkRepository.deleteByDocumentIdAndUserId(document.getId(), document.getUserId());

            List<Chunk> chunks = new ArrayList<>(chunkTexts.size());
            for (ChunkText ct : chunkTexts) {
                chunks.add(Chunk.builder()
                        .documentId(document.getId())
                        .userId(document.getUserId())
                        .chunkIndex(ct.index())
                        .text(ct.text())
                        .charStart(ct.charStart())
                        .charEnd(ct.charEnd())
                        .build());
            }
            // Flush pour que les lignes existent avant l'écriture du vecteur (PgVectorEmbeddingStore).
            List<Chunk> saved = chunkRepository.saveAllAndFlush(chunks);
            for (int i = 0; i < saved.size(); i++) {
                embeddingStore.store(saved.get(i).getId(), vectors.get(i));
            }

            finalizeIndexed(document, saved.size());
            return saved.size();
        } catch (EmbeddingProviderUnavailableException | EmbeddingProviderException ex) {
            // Aucun secret ni contenu journalisé : identifiant du document uniquement.
            log.warn("Ingestion RAG en échec (document={}) — statut FAILED", document.getId());
            document.setStatus(DocumentStatus.FAILED);
            document.setErrorMessage("Échec de l'indexation.");
            documentRepository.save(document);
            return 0;
        }
    }

    private void finalizeIndexed(Document document, int chunkCount) {
        document.setChunkCount(chunkCount);
        document.setStatus(DocumentStatus.INDEXED);
        document.setErrorMessage(null);
        documentRepository.save(document);
    }
}
