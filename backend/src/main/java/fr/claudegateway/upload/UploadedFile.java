package fr.claudegateway.upload;

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
 * Métadonnées d'un fichier téléversé puis transmis au fournisseur IA (F-04). La Gateway ne stocke
 * <b>jamais</b> le contenu binaire : seules les métadonnées nécessaires au relais sont conservées
 * (PROJECT.md §11.6). {@link #userId} est la racine de l'isolation multi-tenant : tout accès filtre
 * sur {@code user_id}.
 */
@Entity
@Table(name = "uploaded_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadedFile {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Identifiant du fichier chez le fournisseur (interne, jamais exposé au client). */
    @Column(name = "provider_file_id", nullable = false, updatable = false, length = 128)
    private String providerFileId;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Column(name = "media_type", nullable = false, length = 128)
    private String mediaType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
