package fr.claudegateway.atelier.git;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.agent.AtelierSessionResult;
import fr.claudegateway.atelier.agent.AtelierSessionService;
import fr.claudegateway.atelier.git.dto.PullRequestResponse;
import fr.claudegateway.git.GitHubClient;
import fr.claudegateway.git.GitProperties;
import fr.claudegateway.git.GitPullRequest;
import fr.claudegateway.git.GitTokenMissingException;
import fr.claudegateway.git.GitTokenService;
import fr.claudegateway.git.InvalidGitBranchException;

/**
 * Ouverture de la <b>pull request</b> depuis l'Atelier (F-31 / SF-31-05, ADR-015).
 *
 * <p><b>Provider-First</b> : la pull request est créée par l'agent, avec l'outil
 * {@code create_pull_request} du serveur <b>MCP GitHub</b>, authentifié par le vault de
 * l'utilisateur. Appeler nous-mêmes {@code POST /repos/.../pulls} depuis la Gateway serait plus
 * simple — et réimplémenterait une capacité que le fournisseur relaie déjà, tout en sortant le jeton
 * de son chemin protégé.</p>
 *
 * <p><b>On vérifie, on ne croit pas</b> : après le tour, l'existence de la pull request est constatée
 * auprès de GitHub. Ce que l'agent déclare ne suffit pas — c'est la même règle qu'au push
 * (SF-31-04), pour la même raison.</p>
 *
 * <p><b>Jamais une session neuve</b> : le tour est joué dans la session en cours, celle qui porte le
 * travail. Une session neuve repartirait d'un clone vierge.</p>
 */
@Service
public class GitPullRequestService {

    private static final Logger log = LoggerFactory.getLogger(GitPullRequestService.class);

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_BODY_LENGTH = 4_000;

    private static final String DEFAULT_BODY =
            "Pull request ouverte depuis l'Atelier Claude Gateway.";

    private final WorkspaceService workspaceService;
    private final AtelierSessionService sessionService;
    private final GitTokenService gitTokenService;
    private final GitHubClient gitHubClient;
    private final GitProperties properties;

    public GitPullRequestService(WorkspaceService workspaceService, AtelierSessionService sessionService,
            GitTokenService gitTokenService, GitHubClient gitHubClient, GitProperties properties) {
        this.workspaceService = workspaceService;
        this.sessionService = sessionService;
        this.gitTokenService = gitTokenService;
        this.gitHubClient = gitHubClient;
        this.properties = properties;
    }

    /**
     * Ouvre la pull request de {@code branch} vers la branche de base du dépôt.
     *
     * @param userId      propriétaire (isolation multi-tenant)
     * @param workspaceId workspace cible
     * @param branch      branche de tête, telle que publiée par {@code /git/push}
     * @param title       titre demandé, ou {@code null} pour un titre dérivé de la branche
     * @param body        description demandée, ou {@code null} pour la description par défaut
     * @return la branche, l'état réellement constaté, l'URL et le numéro de la pull request, et le
     *         compte rendu du tour
     * @throws fr.claudegateway.atelier.WorkspaceNotFoundException     workspace non possédé
     * @throws GitWorkspaceRequiredException                           projet d'archive
     * @throws InvalidGitBranchException                               branche invalide, ou branche de base
     * @throws GitTokenMissingException                                aucun jeton enregistré
     * @throws fr.claudegateway.atelier.agent.NoActiveSessionException aucune session en cours
     * @throws fr.claudegateway.git.GitHubUnavailableException         GitHub injoignable à la vérification
     */
    public PullRequestResponse create(UUID userId, UUID workspaceId, String branch, String title,
            String body) {
        // 1. Isolation d'abord : un workspace d'un autre utilisateur est introuvable.
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);

        // 2. Un projet d'archive n'a pas de dépôt où ouvrir quoi que ce soit.
        if (!workspace.isGit()) {
            throw new GitWorkspaceRequiredException(
                    "Ce projet n'est pas adossé à un dépôt Git : il n'y a pas de pull request à ouvrir.");
        }

        // 3. Branche validée AVANT tout appel : une branche invalide, ou la branche de base, ne doit
        // jamais atteindre la sandbox. Une pull request d'une branche vers elle-même n'existe pas.
        String head = GitRepositoryRef.requireValidBranch(branch);
        String base = workspace.getGitBranch();
        if (head.equals(base)) {
            throw new InvalidGitBranchException(
                    "La pull request part d'une branche dédiée, jamais de « " + base + " » elle-même.");
        }

        // 4. Le jeton doit exister : sans lui, la vérification d'après-coup serait impossible et on
        // annoncerait un résultat qu'on ne peut pas constater.
        String token = gitTokenService.resolveToken(userId)
                .orElseThrow(() -> new GitTokenMissingException(
                        "Aucun jeton GitHub enregistré : ajoutez-en un dans vos réglages."));

        // 5. Le tour, dans la session EXISTANTE. Aucun secret dans l'instruction : le jeton vit dans
        // le vault, chez le fournisseur, et le proxy MCP l'injecte hors du conteneur.
        AtelierSessionResult result = sessionService.runInExistingSession(userId, workspaceId,
                instruction(workspace, head, base, title, body));

        // 6. On constate. Ce que l'agent déclare ne suffit pas.
        Optional<GitPullRequest> pullRequest = gitHubClient.findOpenPullRequest(
                token, workspace.getGitOwner(), workspace.getGitRepo(), head);
        log.info("Ouverture de pull request demandée par l'utilisateur {} : {} (créée={})", userId,
                head, pullRequest.isPresent());

        return new PullRequestResponse(head, pullRequest.isPresent(),
                pullRequest.map(GitPullRequest::url).orElse(null),
                pullRequest.map(GitPullRequest::number).orElse(null),
                result.reply());
    }

    /**
     * Instruction d'ouverture envoyée à l'agent. Elle <b>nomme l'outil</b> attendu : sans cela, un
     * agent bien intentionné tenterait un {@code curl} vers l'API GitHub depuis la sandbox, où il
     * n'a précisément aucun jeton — et échouerait sans comprendre pourquoi.
     *
     * <p>Elle ne contient <b>aucun secret</b>.</p>
     */
    private String instruction(Workspace workspace, String head, String base, String title, String body) {
        return """
                Ouvre une pull request sur le dépôt %s.

                Utilise l'outil « create_pull_request » du serveur MCP « %s ». N'essaie pas d'appeler
                l'API GitHub depuis le terminal : la sandbox ne détient aucun jeton, seul cet outil
                est authentifié.

                Paramètres :
                - owner : %s
                - repo : %s
                - head : %s
                - base : %s
                - title : %s
                - body : %s

                Si l'outil n'est pas disponible, ou si l'appel échoue, dis-le explicitement avec le
                message d'erreur exact, sans le reformuler et sans essayer un autre moyen.
                """.formatted(workspace.getGitOwner() + "/" + workspace.getGitRepo(),
                properties.mcpServerName(), workspace.getGitOwner(), workspace.getGitRepo(), head, base,
                resolveTitle(title, head), resolveBody(body));
    }

    /** Titre demandé, élagué et borné, ou un titre lisible dérivé du nom de la branche. */
    private static String resolveTitle(String title, String head) {
        String candidate = title == null ? "" : title.trim();
        if (candidate.isEmpty()) {
            return "Travaux de l'Atelier : " + head;
        }
        return truncate(candidate, MAX_TITLE_LENGTH);
    }

    private static String resolveBody(String body) {
        String candidate = body == null ? "" : body.trim();
        return candidate.isEmpty() ? DEFAULT_BODY : truncate(candidate, MAX_BODY_LENGTH);
    }

    /**
     * Borne défensive : la validation du DTO couvre déjà la longueur, mais ce service est aussi
     * appelable depuis les tests et une instruction démesurée coûterait du temps de sandbox facturé.
     */
    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
