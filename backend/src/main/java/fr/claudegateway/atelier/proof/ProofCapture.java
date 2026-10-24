package fr.claudegateway.atelier.proof;

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
 * <b>Une capture de preuve</b> (F-212 / SF-212-03) : une image de fenêtre de terminal rendue depuis la
 * sortie brute du poste. Jamais modifiée.
 *
 * <p><b>Isolation.</b> {@code user_id} sur chaque ligne ; toute lecture filtre
 * {@code (id, user_id, workspace_id)} après {@code requireOwned}.</p>
 */
@Entity
@Table(name = "proof_captures")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProofCapture {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "call_id", updatable = false)
    private String callId;

    @Column(name = "title", updatable = false)
    private String title;

    @Column(name = "step", updatable = false)
    private String step;

    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    @Column(name = "host", updatable = false)
    private String host;

    @Column(name = "os", updatable = false)
    private String os;

    @Column(name = "shell", updatable = false)
    private String shell;

    @Column(name = "width", updatable = false)
    private Integer width;

    @Column(name = "height", updatable = false)
    private Integer height;

    @Column(name = "commands_json", updatable = false)
    private String commandsJson;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private OffsetDateTime capturedAt;
}
