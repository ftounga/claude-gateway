package fr.claudegateway.atelier.proposal;

import java.time.OffsetDateTime;
import java.util.UUID;

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
 * <b>Une proposition de gouvernance</b> faite par l'agent (F-177 / SF-177-02, décision D3) : une
 * règle, un skill ou un gabarit, pour le poste ou le sujet. Elle <b>n'écrit rien</b> : elle garde le
 * contenu complet à écrire et l'empreinte du fichier au moment de la proposition. Seul le clic
 * [Appliquer] de l'utilisateur écrit — et refuse si le fichier a changé depuis.
 */
@Entity
@Table(name = "governance_proposals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GovernanceProposal {

    /** Empreinte de base d'un fichier qui n'existait pas encore. */
    public static final String ABSENT = "ABSENT";

    public static final String PENDING = "PENDING";
    public static final String APPLIED = "APPLIED";
    public static final String REFUSED = "REFUSED";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "host_id", updatable = false)
    private UUID hostId;

    /** {@code REGLE} | {@code SKILL} | {@code GABARIT}. */
    @Column(name = "type", nullable = false, length = 16, updatable = false)
    private String type;

    /** {@code POSTE} | {@code SUJET}. */
    @Column(name = "scope", nullable = false, length = 16, updatable = false)
    private String scope;

    @Column(name = "name", nullable = false, length = 120, updatable = false)
    private String name;

    /** Chemin relatif du fichier, à la racine du poste ({@code POSTE}) ou du sujet ({@code SUJET}). */
    @Column(name = "path", nullable = false, length = 300, updatable = false)
    private String path;

    @Column(name = "reason", length = 1000, updatable = false)
    private String reason;

    /** Contenu <b>complet</b> du fichier après application. */
    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    /** Les lignes du diff montré à l'écran (JSON). */
    @Column(name = "diff_json", updatable = false)
    private String diffJson;

    /** Empreinte du fichier au moment de la proposition, ou {@link #ABSENT}. */
    @Column(name = "base_digest", nullable = false, length = 80, updatable = false)
    private String baseDigest;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** Empreinte du contenu écrit, tracée à l'application. */
    @Column(name = "applied_digest", length = 80)
    private String appliedDigest;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;
}
