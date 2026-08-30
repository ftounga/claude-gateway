package fr.claudegateway.runner.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.runner.audit.RunnerAudit;

/**
 * Ligne du journal d'activité du runner telle qu'elle est rendue à son propriétaire
 * (F-38 / SF-38-08). Ni contenu de fichier, ni sortie de commande, ni message d'erreur : le journal
 * dit <b>ce qui a été fait</b>, pas ce qui a été lu.
 *
 * @param id         identifiant de la ligne
 * @param callId     identifiant de corrélation de l'appel (contrat §1)
 * @param tool       outil appelé, ou {@code bootstrap} / {@code kill_switch}
 * @param target     chemin, terme recherché ou commande
 * @param outcome    {@code OK}, {@code ERROR}, {@code DENIED}, {@code TIMEOUT}, {@code CANCELLED}
 * @param errorCode  code d'erreur du contrat §4, {@code null} si l'appel a abouti
 * @param exitCode   code de sortie ({@code bash} uniquement)
 * @param durationMs durée mesurée par le producteur
 * @param bytes      octets lus ou écrits, {@code null} si non renseigné
 * @param createdAt  horodatage de la ligne
 */
public record RunnerAuditResponse(
        UUID id,
        String callId,
        String tool,
        String target,
        String outcome,
        String errorCode,
        Integer exitCode,
        Long durationMs,
        Long bytes,
        OffsetDateTime createdAt) {

    public static RunnerAuditResponse from(RunnerAudit audit) {
        return new RunnerAuditResponse(audit.getId(), audit.getCallId(), audit.getTool(),
                audit.getTarget(), audit.getOutcome(), audit.getErrorCode(), audit.getExitCode(),
                audit.getDurationMs(), audit.getBytes(), audit.getCreatedAt());
    }
}
