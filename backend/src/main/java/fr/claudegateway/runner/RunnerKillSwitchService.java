package fr.claudegateway.runner;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;

/**
 * Coupe-circuit du runner (F-38 / SF-38-08). Deux gestes, un seul principe : <b>ce qui est révoqué
 * doit cesser immédiatement</b>.
 *
 * <ul>
 *   <li><b>Révocation d'un jeton</b> — poser {@code revoked_at} ne suffisait pas : la socket ouverte
 *       sous ce jeton continuait de servir les appels jusqu'à sa propre fermeture. Elle est
 *       désormais coupée sur-le-champ.</li>
 *   <li><b>Coupe-circuit</b> — tous les jetons du projet sont révoqués, la liaison est coupée, et la
 *       cible d'exécution revient à {@code SANDBOX}. Le runner ne peut plus se reconnecter (son
 *       jeton est mort : le handshake le refuse) et la boucle ne route plus rien vers la machine.</li>
 * </ul>
 *
 * <p>Isolation : chaque opération passe par les services existants, qui vérifient l'appartenance du
 * workspace ({@code requireOwned}) — un coupe-circuit ne coupe jamais la machine d'autrui.</p>
 */
@Service
public class RunnerKillSwitchService {

    private static final Logger log = LoggerFactory.getLogger(RunnerKillSwitchService.class);

    private final RunnerTokenService tokenService;
    private final WorkspaceService workspaceService;
    private final RunnerCallDispatcher dispatcher;
    private final RunnerAuditService auditService;

    public RunnerKillSwitchService(RunnerTokenService tokenService, WorkspaceService workspaceService,
            RunnerCallDispatcher dispatcher, RunnerAuditService auditService) {
        this.tokenService = tokenService;
        this.workspaceService = workspaceService;
        this.dispatcher = dispatcher;
        this.auditService = auditService;
    }

    /**
     * Révoque un jeton <b>et</b> coupe la liaison s'il portait la connexion vivante de ce nœud.
     * Idempotent : révoquer un jeton déjà révoqué ne fait rien de plus.
     */
    public void revokeToken(UUID userId, UUID workspaceId, UUID tokenId) {
        boolean holdsConnection = dispatcher.localTokenId(workspaceId)
                .map(tokenId::equals)
                .orElse(false);
        tokenService.revoke(userId, workspaceId, tokenId); // isolation + 404 si non possédé
        if (holdsConnection) {
            dispatcher.disconnect(workspaceId, "session_closed");
        }
    }

    /**
     * Coupe-circuit : révoque tous les jetons encore valides du projet, coupe la liaison, et ramène
     * la cible d'exécution à {@code SANDBOX}.
     *
     * <p>Volontairement <b>idempotent</b> : couper une liaison déjà coupée renvoie un résultat à
     * zéro plutôt qu'une erreur. Un coupe-circuit qui échoue faute d'avoir quelque chose à couper
     * serait un piège au moment précis où l'on en a besoin.</p>
     */
    public KillResult kill(UUID userId, UUID workspaceId) {
        // Isolation d'abord : `list` passe par requireOwned (404 sur un projet d'autrui).
        List<RunnerToken> tokens = tokenService.list(userId, workspaceId);
        OffsetDateTime now = OffsetDateTime.now();
        int revoked = 0;
        for (RunnerToken token : tokens) {
            if (token.isValidAt(now)) {
                tokenService.revoke(userId, workspaceId, token.getId());
                revoked++;
            }
        }
        auditService.recordKillSwitch(userId, workspaceId, revoked);
        boolean disconnected = dispatcher.disconnect(workspaceId, "session_closed");
        Workspace workspace = workspaceService.setExecutionTarget(userId, workspaceId,
                WorkspaceExecutionTarget.SANDBOX);
        log.info("Coupe-circuit runner (workspace={}, jetons révoqués={}, socket fermée={})",
                workspaceId, revoked, disconnected);
        return new KillResult(revoked, disconnected, workspace.executionTargetOrDefault());
    }

    /**
     * Résultat d'un coupe-circuit.
     *
     * @param revokedTokens   nombre de jetons encore valides qui ont été révoqués
     * @param disconnected    vrai si une socket vivante a été fermée sur ce nœud
     * @param executionTarget cible d'exécution du projet après le geste
     */
    public record KillResult(int revokedTokens, boolean disconnected,
            WorkspaceExecutionTarget executionTarget) {
    }
}
