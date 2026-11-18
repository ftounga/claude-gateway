package fr.claudegateway.jarvis.fastbrain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Une délégation de Jarvis</b> (F-272 / SF-272-01) — ce que le cerveau rapide a confié au tour Opus d'un fil
 * {@code (sujet, chantier)}, rangé en base pour que le tour la trouve <b>quel que soit le pod</b> qui l'exécute :
 * offerte (demande reformulée, ligne-lien), prise une seule fois ({@code taken_at}), terminée ({@code finished_at}).
 * Toute lecture filtre par {@code user_id}.
 */
@Entity
@Table(name = "jarvis_delegations")
@Getter
@Setter
@NoArgsConstructor
public class JarvisDelegation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "delegation_id", nullable = false, updatable = false)
    private UUID delegationId;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "chantier")
    private Integer chantier;

    @Column(name = "subject_name", length = 200)
    private String subjectName;

    @Column(name = "label", length = 300)
    private String label;

    @Column(name = "demande", length = 4000)
    private String demande;

    @Column(name = "link_id")
    private UUID linkId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "taken_at")
    private OffsetDateTime takenAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    /** F-270 / SF-270-01 — {@code DELEGATION} (cerveau rapide) ou {@code DIRECT} (tour né à la voix). */
    @Column(name = "origin", nullable = false, length = 16)
    private String origin = ORIGIN_DELEGATION;

    /** F-270 / SF-270-01 — la phase du travail vivant ({@code null} : {@code THINKING}). */
    @Column(name = "phase", length = 16)
    private String phase;

    /** F-270 / SF-270-01 — l'issue à la fin du tour ({@code DONE}, {@code FAILED}, {@code CANCELLED}, {@code WAITING_YES}). */
    @Column(name = "outcome", length = 16)
    private String outcome;

    public static final String ORIGIN_DELEGATION = "DELEGATION";
    public static final String ORIGIN_DIRECT = "DIRECT";
}
