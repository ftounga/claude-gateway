package fr.claudegateway.atelier;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.agent.AtelierAgentProperties;
import fr.claudegateway.atelier.git.GitWorkspaceService;
import fr.claudegateway.atelier.git.GitWorkspaceService.WorkspaceContent;
import fr.claudegateway.runner.RunnerStatusService;
import fr.claudegateway.runner.RunnerStatusService.RunnerStatus;

/**
 * Résout le <b>moteur</b> d'un projet et dit s'il faut proposer le runner (F-39 / SF-39-07).
 *
 * <p>Jusqu'ici cette règle était reconstruite côté écran à partir de trois signaux (source du
 * projet, cible d'exécution, présence d'un runner) et avait déjà été inversée une fois. Le cadrage
 * F-39 (D1) fait du moteur un détail d'implémentation que l'utilisateur ne choisit plus : ce qui
 * n'est plus un choix devient une règle, et une règle appartient à la gateway.</p>
 *
 * <p>Isolation {@code user_id} : le projet est toujours relu via
 * {@link WorkspaceService#requireOwned}, jamais depuis un paramètre client.</p>
 */
@Service
public class AtelierEngineService {

    private final WorkspaceService workspaceService;
    private final GitWorkspaceService gitWorkspaceService;
    private final RunnerStatusService runnerStatusService;
    private final AtelierAgentProperties agentProperties;

    public AtelierEngineService(
            WorkspaceService workspaceService,
            GitWorkspaceService gitWorkspaceService,
            RunnerStatusService runnerStatusService,
            AtelierAgentProperties agentProperties) {
        this.workspaceService = workspaceService;
        this.gitWorkspaceService = gitWorkspaceService;
        this.runnerStatusService = runnerStatusService;
        this.agentProperties = agentProperties;
    }

    /**
     * Moteur du projet, état du runner, et recommandation éventuelle.
     *
     * @throws WorkspaceNotFoundException si le projet n'existe pas <b>ou</b> appartient à quelqu'un
     *         d'autre — les deux cas sont indistinguables, pour ne rien révéler
     */
    @Transactional(readOnly = true)
    public EngineStatus status(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        RunnerStatus runner = runnerStatusService.status(userId, workspaceId);
        AtelierEngine engine = engineOf(workspace);

        // L'arborescence n'est lue que lorsqu'elle peut changer la réponse : hors bac à sable, ou
        // avec un runner déjà connecté, aucune recommandation n'est possible — la lire coûterait un
        // appel à GitHub ou au stockage pour rien.
        RunnerRecommendation reason = engine == AtelierEngine.HOSTED_SANDBOX && !runner.connected()
                ? recommendationFor(userId, workspace)
                : null;

        return new EngineStatus(engine, runner.connected(), runner.lastSeenAt(), reason);
    }

    /**
     * Le moteur suit la <b>cible déclarée</b> du projet, jamais la présence instantanée d'un runner
     * (D-L4-1). Basculer sur le bac à sable dès qu'un runner se déconnecte enverrait le tour suivant
     * dans un espace <b>vide</b> pendant que l'utilisateur croit travailler sur sa machine : c'est
     * exactement le malentendu que D1 supprime, transposé d'un cran. La connexion est un état de
     * santé, rendu à part.
     */
    private AtelierEngine engineOf(Workspace workspace) {
        return workspace.executionTargetOrDefault() == WorkspaceExecutionTarget.RUNNER
                ? AtelierEngine.LOCAL_MACHINE
                : AtelierEngine.HOSTED_SANDBOX;
    }

    /**
     * Motif de recommandation du runner, ou {@code null} si le bac à sable suffit (D6). Un dépôt Git
     * prime sur une arborescence trop grande : c'est le motif que l'utilisateur comprend sans
     * explication.
     */
    private RunnerRecommendation recommendationFor(UUID userId, Workspace workspace) {
        if (workspace.sourceOrDefault() == WorkspaceSource.GIT) {
            return RunnerRecommendation.GIT;
        }
        WorkspaceContent content = gitWorkspaceService.tree(userId, workspace);
        boolean atMountLimit = content.truncated()
                || content.files().size() >= agentProperties.maxSessionFiles();
        return atMountLimit ? RunnerRecommendation.FILE_LIMIT : null;
    }

    /**
     * Moteur d'un projet et état de son runner.
     *
     * @param engine          moteur résolu, jamais choisi par l'utilisateur
     * @param runnerConnected un runner de ce projet est joignable maintenant
     * @param runnerLastSeenAt dernière activité observée, {@code null} si aucun runner ne s'est
     *                        jamais signalé
     * @param recommendReason motif de recommandation du runner, {@code null} s'il n'y a rien à
     *                        proposer
     */
    public record EngineStatus(
            AtelierEngine engine,
            boolean runnerConnected,
            OffsetDateTime runnerLastSeenAt,
            RunnerRecommendation recommendReason) {

        /** Vrai s'il faut proposer le runner : dérivé du motif, jamais renseigné séparément. */
        public boolean recommendRunner() {
            return recommendReason != null;
        }
    }
}
