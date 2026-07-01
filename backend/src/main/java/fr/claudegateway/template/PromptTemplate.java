package fr.claudegateway.template;

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
 * Modèle de prompt réutilisable (F-13) appartenant à un utilisateur unique ({@link #userId}).
 * Racine d'isolation multi-tenant : tout accès filtre sur {@code user_id}. Donnée purement
 * relationnelle — aucun traitement IA n'est attaché à cette entité (le backend reste une Gateway).
 */
@Entity
@Table(name = "prompt_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromptTemplate {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire du modèle (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 16)
    private TemplateCategory category;

    @Column(name = "content", nullable = false, length = 10000)
    private String content;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
