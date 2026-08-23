package fr.claudegateway.atelier;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
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
 * Espace de travail Atelier (F-28 / Claude Code Lite). Un projet importé par un utilisateur : les
 * fichiers vivent dans le {@link fr.claudegateway.atelier.storage.WorkspaceStorage} (objet), jamais
 * en base. {@link #userId} est la racine de l'isolation multi-tenant : tout accès filtre {@code user_id}.
 */
@Entity
@Table(name = "workspaces")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Workspace {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Session sandbox en cours pour ce workspace (F-30 SF-30-04, ADR-014), ou {@code null} si aucune.
     * La sandbox et son système de fichiers survivent d'un message à l'autre : c'est cet identifiant
     * qui les relie.
     */
    @Column(name = "agent_session_id", length = 255)
    private String agentSessionId;

    /** Ouverture de la session courante (diagnostic ; aucune expiration automatique n'en dépend). */
    @Column(name = "agent_session_started_at")
    private OffsetDateTime agentSessionStartedAt;

    /**
     * Dernier relevé d'usage de la session courante. {@code getSessionUsage} renvoie un <b>cumul</b>
     * depuis l'ouverture : seul l'écart avec ces valeurs doit être décompté, sans quoi la même
     * consommation serait facturée à chaque tour, de plus en plus cher.
     */
    @Column(name = "agent_input_tokens", nullable = false)
    private long agentInputTokens;

    @Column(name = "agent_output_tokens", nullable = false)
    private long agentOutputTokens;

    @Column(name = "agent_active_seconds", nullable = false)
    private long agentActiveSeconds;
}
