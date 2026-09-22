package fr.claudegateway.images;

import java.math.BigDecimal;
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
 * Une <b>image décorative générée par IA</b> (F-142 / SF-142-04) : la gateway a relayé un fournisseur
 * d'images (OpenAI), rangé le PNG dans le stockage objet et déposé une copie dans le projet.
 *
 * <p><b>Isolation.</b> {@code user_id} scelle toute lecture (jamais un paramètre client) ; {@code space}
 * et {@code host_id} rangent l'image du bon côté (Forge/Vigie) — même patron que les pages (F-109) et les
 * présentations (F-129). {@code host_id}/{@code workspace_id} peuvent être {@code null} (projet sans poste).</p>
 *
 * <p>Le fichier lui-même vit dans le <b>stockage objet</b> ({@link GeneratedImageStore}) ; l'entité n'en
 * porte que la <b>clé</b>, la taille et le coût. {@code status} porte l'état lisible de la génération.</p>
 */
@Entity
@Table(name = "generated_images")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeneratedImage {

    public static final int MAX_PROMPT_LENGTH = 4000;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "space", nullable = false, length = 8, updatable = false)
    private ImageSpace space;

    /** Poste (Forge) ou client (Vigie) ; {@code null} pour un projet sans poste. */
    @Column(name = "host_id", updatable = false)
    private UUID hostId;

    /** Projet ou terminal d'origine ; {@code null} admis. */
    @Column(name = "workspace_id", updatable = false)
    private UUID workspaceId;

    /** La description demandée (donnée de l'utilisateur), bornée. */
    @Column(name = "prompt", nullable = false, length = MAX_PROMPT_LENGTH)
    private String prompt;

    /** La taille demandée (valeur de la liste blanche, ex. {@code 1024x1024}). */
    @Column(name = "size", nullable = false, length = 16)
    private String size;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 8)
    private GeneratedImageStatus status;

    /** Clé du PNG dans le stockage objet ; {@code null} tant que la génération n'est pas READY. */
    @Column(name = "image_key", length = 300)
    private String imageKey;

    /** Taille du PNG en octets ; {@code null} tant que non READY. */
    @Column(name = "image_bytes")
    private Long imageBytes;

    /** Coût enregistré de la génération (EUR) ; {@code null} tant que non READY. */
    @Column(name = "cost_eur", precision = 12, scale = 4)
    private BigDecimal costEur;

    /** Motif d'échec (FAILED) ; {@code null} sinon. */
    @Column(name = "error", length = 500)
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
