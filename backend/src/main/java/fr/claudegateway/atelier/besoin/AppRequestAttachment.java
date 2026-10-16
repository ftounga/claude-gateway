package fr.claudegateway.atelier.besoin;

import java.time.OffsetDateTime;
import java.util.UUID;

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
 * <b>Une pièce d'un besoin</b> (F-205 / SF-205-01) : un fichier déposé dans le terminal, copié dans le
 * stockage de la gateway au clic [Enregistrer] — sur un poste, les octets resteraient sinon sur la
 * machine.
 */
@Entity
@Table(name = "app_request_attachments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppRequestAttachment {

    public static final String PENDING = "PENDING";
    public static final String COPIED = "COPIED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Rang de la pièce dans le besoin, à partir de 1 : c'est le {@code n} de la route de lecture. */
    @Column(name = "seq", nullable = false, updatable = false)
    private int seq;

    @Column(name = "name", nullable = false, length = 255, updatable = false)
    private String name;

    @Column(name = "content_type", nullable = false, length = 120, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    /** Chemin du dépôt dans le terminal. */
    @Column(name = "source_path", nullable = false, length = 1024, updatable = false)
    private String sourcePath;

    @Column(name = "storage_key", length = 1024)
    private String storageKey;

    /** {@code null} tant que le besoin est proposé ; {@code PENDING} | {@code COPIED} | {@code FAILED}. */
    @Column(name = "copy_status", length = 16)
    private String copyStatus;

    @Column(name = "copy_error", length = 500)
    private String copyError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
