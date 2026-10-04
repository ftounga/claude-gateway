package fr.claudegateway.governance.map.index;

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
 * <b>Une section de la carte, telle que l'index la connaît</b> (F-174 / SF-174-02, D3).
 *
 * <p>Une section = un titre {@code ##} et ce qui le suit. Son empreinte dit si son texte a changé :
 * seule une section dont l'empreinte change est ré-extraite. Ses faits, entités et relations pendent
 * à elle et disparaissent avec elle.</p>
 */
@Entity
@Table(name = "host_map_sections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostMapSection {

    /** L'extraction sémantique reste à faire. */
    public static final String PENDING = "PENDING";
    /** L'extraction sémantique est faite. */
    public static final String DONE = "DONE";
    /** Rien à extraire (section sans fait). */
    public static final String SKIPPED = "SKIPPED";
    /** L'extraction a échoué trop de fois ; la couche déterministe reste. */
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "path", nullable = false, length = 500, updatable = false)
    private String path;

    @Column(name = "heading", length = 500)
    private String heading;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "fingerprint", nullable = false, length = 64, updatable = false)
    private String fingerprint;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "extracted_at")
    private OffsetDateTime extractedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
