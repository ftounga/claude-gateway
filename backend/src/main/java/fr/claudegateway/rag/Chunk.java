package fr.claudegateway.rag;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Fragment de texte issu du découpage d'un document (F-06 / SF-06-01). {@link #userId} est la racine
 * de l'isolation multi-tenant : tout accès filtre sur {@code user_id} (et permet le filtre direct de
 * la recherche vectorielle F-07). {@link #documentId} référence le document source ({@code documents}).
 *
 * <p>La colonne vectorielle {@code embedding vector(1536)} (Postgres uniquement, cf. migration 011)
 * n'est <b>volontairement pas mappée</b> ici : sa persistance passe par
 * {@link fr.claudegateway.rag.store.EmbeddingStore} (SQL natif pgvector / no-op ailleurs), afin de
 * garder la validation Hibernate et les tests H2 verts sans type vectoriel.</p>
 */
@Entity
@Table(name = "chunks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Chunk {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Document source (= {@code documents.id}). */
    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    /** Propriétaire (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Position ordinale du chunk dans le document (0..N-1). */
    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "text", nullable = false, columnDefinition = "text")
    private String text;

    /** Offset de début dans le texte source (inclus). */
    @Column(name = "char_start")
    private Integer charStart;

    /** Offset de fin dans le texte source (exclu). */
    @Column(name = "char_end")
    private Integer charEnd;

    /** Numéro de page (non dérivé en F-06 : {@code null} ; citations paginées = F-07). */
    @Column(name = "page_number")
    private Integer pageNumber;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
