package fr.claudegateway.presentations;

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
 * Un <b>artefact « présentation »</b> (F-129 / SF-129-02) : un vrai fichier {@code .pptx} produit par
 * l'agent sur le terminal, capturé dans l'application, rattaché à un poste/projet.
 *
 * <p><b>Isolation.</b> {@code user_id} scelle toute lecture (jamais un paramètre client) ; {@code space}
 * et {@code host_id} rangent l'artefact du bon côté (Forge/Vigie) — même patron que les pages (F-109).
 * {@code host_id}/{@code workspace_id} peuvent être {@code null} (projet sans poste), comme une page.</p>
 *
 * <p>Le fichier lui-même vit dans le <b>stockage objet</b> ({@link PresentationStore}) ; l'entité n'en
 * porte que la <b>clé</b> et la taille. {@code slideCount} reste {@code null} tant que le rendu par
 * slides (SF-129-03) n'a pas été produit.</p>
 */
@Entity
@Table(name = "presentations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Presentation {

    public static final int MAX_TITLE_LENGTH = 120;
    public static final int MAX_DESCRIPTION_LENGTH = 300;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "space", nullable = false, length = 8, updatable = false)
    private PresentationSpace space;

    /** Poste (Forge) ou client (Vigie) ; {@code null} pour un projet sans poste. */
    @Column(name = "host_id", updatable = false)
    private UUID hostId;

    /** Projet ou terminal d'origine ; {@code null} admis. */
    @Column(name = "workspace_id", updatable = false)
    private UUID workspaceId;

    @Column(name = "title", nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @Column(name = "description", length = MAX_DESCRIPTION_LENGTH)
    private String description;

    /** Clé du fichier {@code .pptx} dans le stockage objet (F-129 / SF-129-02). */
    @Column(name = "pptx_key", nullable = false, length = 300)
    private String pptxKey;

    /** Taille du {@code .pptx} en octets. */
    @Column(name = "pptx_bytes", nullable = false)
    private long pptxBytes;

    /** Nombre de slides rendues en images (F-129 / SF-129-03), ou {@code null} tant qu'aucun rendu. */
    @Column(name = "slide_count")
    private Integer slideCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
