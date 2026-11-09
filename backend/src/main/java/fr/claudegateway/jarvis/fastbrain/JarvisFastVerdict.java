package fr.claudegateway.jarvis.fastbrain;

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

/**
 * F-252 / SF-252-02 (D3) — le verdict de l'écran sur une réponse provisoire : déposé par un pod, lu par celui qui tient
 * le flux, consommé puis supprimé. Toujours lu sous {@code user_id}.
 */
@Entity
@Table(name = "jarvis_fast_verdicts")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JarvisFastVerdict {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "speculation_id", nullable = false, updatable = false)
    private UUID speculationId;

    @Column(name = "adopted", nullable = false, updatable = false)
    private boolean adopted;

    /** Le texte de référence (retranscription) ; {@code null} pour un verdict de rejet. */
    @Column(name = "text", length = 4000)
    private String text;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
