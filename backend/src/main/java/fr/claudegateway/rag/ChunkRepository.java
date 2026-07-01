package fr.claudegateway.rag;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistance des chunks d'ingestion RAG (F-06). Toute opération propre à un utilisateur filtre sur
 * {@code user_id} (isolation multi-tenant). Aucune logique métier ici.
 */
@Repository
public interface ChunkRepository extends JpaRepository<Chunk, UUID> {

    /** Chunks d'un document appartenant à l'utilisateur, dans l'ordre de découpage (isolation). */
    List<Chunk> findByDocumentIdAndUserIdOrderByChunkIndexAsc(UUID documentId, UUID userId);

    /**
     * Charge un lot de chunks par id en garantissant qu'ils appartiennent à l'utilisateur (isolation
     * multi-tenant, défense en profondeur au-dessus de la recherche vectorielle déjà filtrée F-07).
     * L'ordre du retour n'est pas garanti : l'appelant ré-ordonne selon la pertinence.
     */
    List<Chunk> findByIdInAndUserId(Collection<UUID> ids, UUID userId);

    /** Nombre de chunks d'un document de l'utilisateur (isolation). */
    long countByDocumentIdAndUserId(UUID documentId, UUID userId);

    /**
     * Supprime les chunks d'un document de l'utilisateur (idempotence de la ré-ingestion, isolation).
     *
     * @return le nombre de chunks supprimés
     */
    @Transactional
    long deleteByDocumentIdAndUserId(UUID documentId, UUID userId);
}
