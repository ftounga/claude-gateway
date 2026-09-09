package fr.claudegateway.runner;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 *   <li><b>Coupe-circuit</b> — tous les jetons du <b>poste</b> sont révoqués, la liaison est coupée,
 *       et <b>tous les projets</b> qui vivaient dessus reviennent en cible {@code SANDBOX}. Le
 *       runner ne peut plus se reconnecter (son jeton est mort : le handshake le refuse) et la
 *       boucle ne route plus rien vers la machine.</li>
 * </ul>
 *
 * <p>C'est le déplacement d'unité de F-48 / SF-48-01 : on ne coupe pas un dossier, on coupe une
 * <b>machine</b>. Ne ramener qu'un projet au bac à sable aurait laissé les autres pointer vers un
 * runner mort — un coupe-circuit qui ne coupe pas tout n'en est pas un.</p>
 *
 * <p>Isolation : chaque opération passe par les services existants, qui vérifient l'appartenance du
 * poste — un coupe-circuit ne coupe jamais la machine d'autrui.</p>
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
    @Transactional
    public void revokeToken(UUID userId, UUID hostId, UUID tokenId) {
        boolean holdsConnection = dispatcher.localTokenId(hostId)
                .map(tokenId::equals)
                .orElse(false);
        tokenService.revoke(userId, hostId, tokenId); // isolation + 404 si non possédé
        if (holdsConnection) {
            dispatcher.disconnect(hostId, "session_closed");
        }
    }

    /**
     * Coupe-circuit d'un poste : révoque tous ses jetons encore valides, coupe la liaison, et ramène
     * <b>tous ses projets</b> à la cible {@code SANDBOX}.
     *
     * <p>Volontairement <b>idempotent</b> : couper une liaison déjà coupée renvoie un résultat à
     * zéro plutôt qu'une erreur. Un coupe-circuit qui échoue faute d'avoir quelque chose à couper
     * serait un piège au moment précis où l'on en a besoin.</p>
     */
    @Transactional
    public KillResult kill(UUID userId, UUID hostId) {
        // Isolation d'abord : `list` passe par requireOwned (404 sur le poste d'autrui).
        List<RunnerToken> tokens = tokenService.list(userId, hostId);
        OffsetDateTime now = OffsetDateTime.now();
        int revoked = 0;
        for (RunnerToken token : tokens) {
            if (token.isValidAt(now)) {
                tokenService.revoke(userId, hostId, token.getId());
                revoked++;
            }
        }
        auditService.recordKillSwitch(userId, hostId, revoked);
        boolean disconnected = dispatcher.disconnect(hostId, "session_closed");
        int returned = 0;
        for (Workspace workspace : workspaceService.listByHost(userId, hostId)) {
            if (workspace.isRunnerTarget()) {
                workspaceService.setExecutionTarget(userId, workspace.getId(),
                        WorkspaceExecutionTarget.SANDBOX);
                returned++;
            }
        }
        log.info("Coupe-circuit runner (poste={}, jetons révoqués={}, socket fermée={}, projets "
                + "ramenés au bac à sable={})", hostId, revoked, disconnected, returned);
        return new KillResult(revoked, disconnected, returned);
    }

    /**
     * Résultat d'un coupe-circuit.
     *
     * @param revokedTokens      nombre de jetons encore valides qui ont été révoqués
     * @param disconnected       vrai si une socket vivante a été fermée sur ce nœud
     * @param workspacesReturned projets ramenés à la cible {@code SANDBOX}
     */
    public record KillResult(int revokedTokens, boolean disconnected, int workspacesReturned) {
    }
}
