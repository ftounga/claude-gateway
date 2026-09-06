package fr.claudegateway.atelier;

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

    /**
     * Provenance des fichiers (F-31 / SF-31-02) : {@code ARCHIVE} (zip téléversé, historique) ou
     * {@code GIT} (dépôt monté par le fournisseur). {@code ARCHIVE} par défaut, y compris pour les
     * lignes créées avant la migration 043.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    @Builder.Default
    private WorkspaceSource source = WorkspaceSource.ARCHIVE;

    /** URL publique du dépôt monté ({@code https://github.com/owner/repo}), source {@code GIT} seule. */
    @Column(name = "git_repo_url", length = 500)
    private String gitRepoUrl;

    /** Propriétaire du dépôt (organisation ou utilisateur GitHub). */
    @Column(name = "git_owner", length = 100)
    private String gitOwner;

    /** Nom du dépôt. */
    @Column(name = "git_repo", length = 100)
    private String gitRepo;

    /**
     * Branche montée dans la sandbox. C'est aussi la <b>branche de base</b> d'un push : y pousser est
     * refusé (ADR-015). Aucun secret : le jeton d'accès vit chiffré dans {@code user_git_credentials}.
     */
    @Column(name = "git_branch", length = 255)
    private String gitBranch;

    /**
     * Cible d'exécution des outils (F-38 / SF-38-05, décision D1) : {@code SANDBOX} (historique) ou
     * {@code RUNNER} (machine de l'utilisateur, via le canal WebSocket runner). {@code SANDBOX} par
     * défaut, y compris pour les lignes créées avant la migration 048.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "execution_target", nullable = false, length = 16)
    @Builder.Default
    private WorkspaceExecutionTarget executionTarget = WorkspaceExecutionTarget.SANDBOX;

    /**
     * Nom du dossier déclaré par le runner à l'appairage (F-38 / SF-38-15) — le <b>dernier segment
     * seulement</b>, jamais le chemin absolu. Sert uniquement à l'affichage. Nul tant qu'aucun
     * runner ne s'est appairé, ou si le runner est antérieur à cette version.
     */
    @Column(name = "runner_root_name", length = 255)
    private String runnerRootName;

    /**
     * Vrai si le runner appairé tourne avec les droits de l'<b>administrateur</b>
     * (F-38 / SF-38-18). Déclaré par le runner : la gateway ne peut pas le deviner. Sert à le dire
     * là où l'on autorise une commande — c'est le seul endroit où l'information change une décision.
     */
    @Column(name = "runner_elevated")
    private Boolean runnerElevated;

    /**
     * Vrai si le projet est adossé à un dépôt Git (F-31 / SF-31-02). Volontairement null-tolérant :
     * une entité construite hors du builder (tests, désérialisation partielle) n'est pas un projet
     * Git, et le chemin le plus sûr — celui de l'archive — reste le comportement par défaut.
     */
    public boolean isGit() {
        return source == WorkspaceSource.GIT;
    }

    /** Source du projet, {@code ARCHIVE} par défaut si elle n'a pas été renseignée. */
    public WorkspaceSource sourceOrDefault() {
        return source == null ? WorkspaceSource.ARCHIVE : source;
    }

    /**
     * Cible d'exécution, {@code SANDBOX} par défaut si elle n'a pas été renseignée. Même discipline
     * null-tolérante que {@link #sourceOrDefault()} : une entité construite hors du builder retombe
     * sur le chemin historique, jamais sur le mode le plus puissant.
     */
    public WorkspaceExecutionTarget executionTargetOrDefault() {
        return executionTarget == null ? WorkspaceExecutionTarget.SANDBOX : executionTarget;
    }

    /** Vrai si les outils de ce projet s'exécutent sur la machine de l'utilisateur (F-38 / SF-38-05). */
    /** Vrai si le projet vit sur la machine de l'utilisateur (F-38 / SF-38-15). */
    public boolean isLocal() {
        return source == WorkspaceSource.LOCAL;
    }

    public boolean isRunnerTarget() {
        return executionTargetOrDefault() == WorkspaceExecutionTarget.RUNNER;
    }

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Frontière de rejeu du fil d'Atelier (F-39 / SF-39-04, décision D5) : les messages antérieurs
     * ne repartent plus chez le fournisseur. {@code null} — le cas de tous les projets existants —
     * signifie que tout l'historique est rejoué, exactement comme avant.
     *
     * <p>Un « nouveau départ » <b>ne supprime rien</b> : la conversation reste lisible à l'écran,
     * seule change la mémoire que l'agent en a. C'est ce qui rend le geste réversible.</p>
     */
    @Column(name = "chat_thread_started_at")
    private OffsetDateTime chatThreadStartedAt;

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

    /**
     * Coût cumulé de la session courante en <b>unités mineures</b> (F-36 / SF-36-02) : ce que le
     * fournisseur facture réellement (tokens au tarif du modèle servi, recherches web, temps de bac à
     * sable). Même rôle que les compteurs ci-dessus — seul l'écart avec ce relevé est décompté.
     */
    @Column(name = "agent_list_cost", nullable = false)
    private long agentListCost;

    /**
     * Demander l'autorisation avant d'exécuter une commande (F-33 / SF-33-01). Lue à l'<b>ouverture</b>
     * de session : la politique d'outils est fixée pour toute la vie de la session, une bascule ne
     * change donc pas une sandbox déjà ouverte.
     *
     * <p>{@code false} par défaut — qui n'active rien garde exactement le comportement d'avant F-33
     * ({@code always_allow}), et aucune session ne peut rester bloquée en attente d'une confirmation
     * que personne n'attend.</p>
     */
    @Column(name = "agent_ask_before_bash", nullable = false)
    private boolean agentAskBeforeBash;
}
