package fr.claudegateway.ocr;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Document soumis au pipeline OCR (F-05). {@link #userId} est la racine de l'isolation multi-tenant :
 * tout accès filtre sur {@code user_id}. Le contenu binaire n'est pas conservé en base — seuls le
 * texte extrait ({@link #extractedText}) et le brut fournisseur ({@link #textractRaw}, audit /
 * reprocessing F-06) sont persistés.
 */
@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Column(name = "media_type", nullable = false, length = 128)
    private String mediaType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DocumentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "ocr_mode", nullable = false, length = 16)
    private OcrMode ocrMode;

    /** Identifiant du job chez le fournisseur OCR (interne, jamais exposé au client). */
    @Column(name = "provider_job_id", length = 128)
    private String providerJobId;

    @Column(name = "extracted_text", columnDefinition = "text")
    private String extractedText;

    /** Réponse brute du fournisseur OCR (audit / reprocessing). Jamais exposée au client. */
    @Column(name = "textract_raw", columnDefinition = "text")
    private String textractRaw;

    /** Message d'erreur métier neutre en cas d'échec OCR/indexation (jamais un détail brut fournisseur). */
    @Column(name = "error_message", length = 255)
    private String errorMessage;

    /** Nombre de chunks indexés (F-06). 0 tant que le document n'est pas {@code INDEXED}. */
    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
