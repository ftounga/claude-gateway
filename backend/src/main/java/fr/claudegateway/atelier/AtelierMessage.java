package fr.claudegateway.atelier;

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
 * Message d'une conversation « Atelier » (F-28 / SF-28-02), attachée à un workspace. Isolation
 * multi-tenant : {@code user_id} porté sur chaque ligne ; tout accès filtre dessus.
 */
@Entity
@Table(name = "atelier_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AtelierMessage {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** {@code USER} ou {@code ASSISTANT}. */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Transcription du tour en mode Terminal (F-30 SF-30-09) : commandes, sorties, échec et coût,
     * sérialisés en JSON. {@code null} pour les tours du mode Assistant, qui n'en ont pas.
     *
     * <p>Donnée d'<b>affichage</b> : restituée en bloc à l'historique, jamais requêtée — d'où un
     * document plutôt qu'une table de blocs.</p>
     */
    @Column(name = "terminal_json", columnDefinition = "text")
    private String terminalJson;

    /**
     * Trajectoire d'outils du tour (F-39 / SF-39-03) : appels, arguments et résultats bornés,
     * sérialisés en JSON. {@code null} quand le tour n'a appelé aucun outil, et pour tous les
     * messages antérieurs à SF-39-03 — qui restent rejoués en texte seul.
     *
     * <p>Donnée de <b>rejeu</b>, jamais d'affichage : elle repart chez le fournisseur au tour
     * suivant pour que l'agent ne refasse pas ce qu'il vient de faire. Ce que l'utilisateur relit
     * reste porté par {@code terminal_json}.</p>
     */
    @Column(name = "tool_trace", columnDefinition = "text")
    private String toolTrace;
}
