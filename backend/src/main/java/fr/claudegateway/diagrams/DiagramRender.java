package fr.claudegateway.diagrams;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Un schéma conservé</b> (F-237 / SF-237-03) : la description reçue par {@code render_diagram} et les clés des
 * fichiers rendus, écrits <b>après</b> un rendu réussi. Lu uniquement par son propriétaire.
 */
@Entity
@Table(name = "diagram_renders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiagramRender {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "call_id", length = 120, updatable = false)
    private String callId;

    @Column(name = "chantier_number", updatable = false)
    private Integer chantierNumber;

    /** {@code mermaid}, {@code cloud} ou {@code drawio}. */
    @Column(name = "engine", nullable = false, length = 16, updatable = false)
    private String engine;

    @Column(name = "title", length = 200, updatable = false)
    private String title;

    /** La {@code spec} telle que reçue (groupes, nœuds, liens) — moteurs {@code cloud} et {@code drawio}. */
    @Column(name = "spec_json", columnDefinition = "text", updatable = false)
    private String specJson;

    /** Le code Mermaid — moteur {@code mermaid}. */
    @Column(name = "code", columnDefinition = "text", updatable = false)
    private String code;

    @Column(name = "svg_key", length = 300, updatable = false)
    private String svgKey;

    @Column(name = "png_key", length = 300, updatable = false)
    private String pngKey;

    @Column(name = "drawio_key", length = 300, updatable = false)
    private String drawioKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Architecture (icônes cloud, draw.io) ou simple diagramme (Mermaid). */
    public boolean isArchitecture() {
        return !"mermaid".equalsIgnoreCase(engine);
    }
}
