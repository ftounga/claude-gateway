package fr.claudegateway.atelier;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * <b>Lire un fichier binaire là où vit le projet</b> — poste (par tranches) ou hébergé.
 *
 * <p>Symétrique de {@link ProjectFileDeposit}, et extrait pour la même raison : la lecture du
 * {@code .pptx} (F-129 / SF-129-02) et celle des images d'un deck construit par la gateway
 * (SF-129-05) sont le même geste. Deux copies auraient fini par diverger.</p>
 *
 * <p><b>Isolation</b> : le {@link Workspace} vient du tour ; aucun chemin absolu, aucune remontée de
 * dossier n'est acceptée — l'appelant valide le chemin avant d'appeler.</p>
 */
@Component
public class ProjectFileRead {

    /** Tranche lue par appel : ≈ 480 Kio en Base64, sous la borne de 512 Kio du contrat. */
    public static final int CHUNK_BYTES = 360 * 1024;

    /** Ce qu'on a lu, ou pourquoi on n'a pas pu. */
    public record Read(byte[] content, String error) {

        public static Read ok(byte[] content) {
            return new Read(content, null);
        }

        public static Read error(String message) {
            return new Read(null, message);
        }

        public boolean failed() {
            return error != null;
        }
    }

    private final WorkspaceService workspaceService;
    private final RunnerToolGateway runnerToolGateway;
    private final RunnerAuditService runnerAuditService;

    public ProjectFileRead(WorkspaceService workspaceService, RunnerToolGateway runnerToolGateway,
            RunnerAuditService runnerAuditService) {
        this.workspaceService = workspaceService;
        this.runnerToolGateway = runnerToolGateway;
        this.runnerAuditService = runnerAuditService;
    }

    /** Les octets d'un fichier du projet, bornés — au-delà, un refus dit. */
    public Read read(UUID userId, Workspace workspace, String callId, String path, long cap, String tool) {
        if (workspace.isRunnerTarget()) {
            return fromRunner(userId, RunnerTargets.of(workspace), callId, path, cap, tool);
        }
        try {
            byte[] content = workspaceService.readFileBytes(userId, workspace.getId(), path);
            if (content.length > cap) {
                return Read.error("Fichier trop lourd (" + path + ") : maximum " + cap + " octets.");
            }
            return Read.ok(content);
        } catch (RuntimeException e) {
            return Read.error("Fichier illisible dans le projet hébergé (" + path + ").");
        }
    }

    private Read fromRunner(UUID userId, RunnerTarget target, String callId, String path, long cap,
            String tool) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RunnerCallResult last = null;
        int chunk = 0;
        while (true) {
            RunnerCallResult result = runnerToolGateway.readFileBytes(target, callId + "." + chunk, path,
                    out.size(), CHUNK_BYTES);
            last = result;
            if (!result.ok()) {
                runnerAuditService.recordCall(userId, target, callId, tool, path, result);
                if (chunk == 0 && RunnerErrorCodes.UNSUPPORTED_TOOL.equals(result.errorCode())) {
                    return Read.error("Le runner de ce poste est trop ancien pour lire un fichier binaire : "
                            + "mets-le à jour depuis la Forge.");
                }
                return Read.error("Fichier illisible sur la machine (" + path + ").");
            }
            byte[] part;
            try {
                part = Base64.getDecoder().decode(result.content() == null ? "" : result.content());
            } catch (IllegalArgumentException e) {
                runnerAuditService.recordCall(userId, target, callId, tool, path, result);
                return Read.error("Fichier illisible sur la machine (" + path + ") : réponse invalide.");
            }
            out.writeBytes(part);
            if (out.size() > cap) {
                runnerAuditService.recordCall(userId, target, callId, tool, path, result);
                return Read.error("Fichier trop lourd (" + path + ") : maximum " + cap + " octets.");
            }
            if (!result.truncated()) {
                runnerAuditService.recordCall(userId, target, callId, tool, path, last);
                return Read.ok(out.toByteArray());
            }
            chunk++;
        }
    }
}
