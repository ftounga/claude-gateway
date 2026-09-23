package fr.claudegateway.atelier;

import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerTargets;

/**
 * <b>Déposer un fichier binaire là où vit le projet</b> — poste ou hébergé.
 *
 * <p>Ce chemin existait depuis F-142 / SF-142-04 (les images décoratives) ; il est extrait ici pour que
 * le <b>rendu de diagrammes</b> (SF-142-06) l'emprunte au lieu de le recopier. Deux copies du même
 * dépôt finiraient par diverger, et c'est précisément sur ce genre d'écart qu'un livrable se casse.</p>
 *
 * <p><b>Isolation</b> : le {@link Workspace} vient du tour, déjà vérifié comme possédé ; aucun chemin
 * n'est accepté du modèle — seulement un nom de fichier, nettoyé par l'appelant.</p>
 */
@Component
public class ProjectFileDeposit {

    /** Tranche binaire écrite par appel : ≈ 480 Kio en Base64, sous la borne de 512 Kio du contrat. */
    public static final int CHUNK_BYTES = 360 * 1024;

    private final WorkspaceService workspaceService;
    private final fr.claudegateway.runner.exec.RunnerToolGateway runnerToolGateway;
    private final RunnerAuditService runnerAuditService;

    public ProjectFileDeposit(WorkspaceService workspaceService,
            fr.claudegateway.runner.exec.RunnerToolGateway runnerToolGateway,
            RunnerAuditService runnerAuditService) {
        this.workspaceService = workspaceService;
        this.runnerToolGateway = runnerToolGateway;
        this.runnerAuditService = runnerAuditService;
    }

    /**
     * Dépose le fichier et rend son nom, ou {@code null} si le dépôt n'a pas abouti — l'appelant le
     * <b>dit</b> alors, plutôt que de laisser croire que le fichier est là.
     *
     * @param tool le nom d'outil sous lequel tracer l'écriture
     */
    public String deposit(UUID userId, Workspace workspace, String callId, String name, byte[] bytes,
            String contentType, String tool) {
        if (workspace.isRunnerTarget()) {
            return depositOnRunner(userId, workspace, callId, name, bytes, tool);
        }
        try {
            return workspaceService.depositHostedFile(userId, workspace.getId(), name, bytes, contentType);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Pousse le fichier sur le poste par tranches ({@code write_file_bytes}, F-115) : {@code offset == 0}
     * tronque/crée. L'écriture est tracée une fois, sur le {@code callId} de l'appel.
     */
    private String depositOnRunner(UUID userId, Workspace workspace, String callId, String name,
            byte[] bytes, String tool) {
        RunnerTarget target = RunnerTargets.of(workspace);
        RunnerCallResult last = null;
        int offset = 0;
        int chunk = 0;
        while (offset < bytes.length) {
            int end = Math.min(bytes.length, offset + CHUNK_BYTES);
            String base64 = Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(bytes, offset, end));
            RunnerCallResult result = runnerToolGateway.writeFileBytes(target, callId + "." + chunk, name,
                    base64, offset);
            last = result;
            if (!result.ok()) {
                runnerAuditService.recordCall(userId, target, callId, tool, name, result);
                if (chunk == 0 && RunnerErrorCodes.UNSUPPORTED_TOOL.equals(result.errorCode())) {
                    // Runner trop ancien : pas de dépôt binaire. Le tour continue sans fichier déposé.
                    return null;
                }
                return null;
            }
            offset = end;
            chunk++;
        }
        runnerAuditService.recordCall(userId, target, callId, tool, name, last);
        return name;
    }
}
