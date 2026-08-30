package fr.claudegateway.runner.audit;

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
 * Ligne du journal d'audit du runner (F-38 / SF-38-08, décision D11) : <b>une par appel d'outil</b>
 * exécuté sur la machine de l'utilisateur, et une par appel <b>refusé avant émission</b> (validation
 * d'action). C'est ce qui permet de dire précisément ce qui a été lu, écrit et exécuté.
 *
 * <p>Ce que cette table ne contient <b>pas</b>, délibérément : aucun contenu de fichier, aucune
 * sortie de commande, aucun message d'erreur du runner — un message peut porter un fragment de
 * chemin de la machine, un code d'erreur jamais. Seuls l'outil, la cible, l'issue et des mesures.</p>
 */
@Entity
@Table(name = "runner_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RunnerAudit {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire du workspace (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /** Jeton du runner qui a servi l'appel, ou {@code null} si l'appel n'a jamais été émis. */
    @Column(name = "token_id", updatable = false)
    private UUID tokenId;

    /**
     * Identifiant de corrélation du contrat de messages §1 (= {@code tool_use} du fournisseur) : la
     * même clef relie la trame WebSocket, l'événement SSE de confirmation et cette ligne.
     */
    @Column(name = "call_id", nullable = false, length = 64, updatable = false)
    private String callId;

    /** Outil appelé, ou {@code bootstrap} / {@code kill_switch} pour les gestes de la gateway. */
    @Column(name = "tool", nullable = false, length = 32, updatable = false)
    private String tool;

    /** Chemin, terme recherché ou commande tronquée. Jamais un contenu. */
    @Column(name = "target", length = 1000, updatable = false)
    private String target;

    /** {@code OK} | {@code ERROR} | {@code DENIED} | {@code TIMEOUT} | {@code CANCELLED}. */
    @Column(name = "outcome", nullable = false, length = 16, updatable = false)
    private String outcome;

    /** Code de la liste close du contrat §4, {@code null} si l'appel a abouti. */
    @Column(name = "error_code", length = 32, updatable = false)
    private String errorCode;

    /** Code de sortie, uniquement pour {@code bash}. */
    @Column(name = "exit_code", updatable = false)
    private Integer exitCode;

    @Column(name = "duration_ms", updatable = false)
    private Long durationMs;

    /** Octets lus ou écrits, {@code null} si le producteur ne les renseigne pas. */
    @Column(name = "bytes", updatable = false)
    private Long bytes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
