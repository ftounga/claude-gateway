package fr.claudegateway.atelier.git;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.agent.AtelierSessionResult;
import fr.claudegateway.atelier.agent.AtelierSessionService;
import fr.claudegateway.atelier.git.dto.GitPushResponse;
import fr.claudegateway.git.GitHubClient;
import fr.claudegateway.git.GitTokenMissingException;
import fr.claudegateway.git.GitTokenService;
import fr.claudegateway.git.InvalidGitBranchException;

/**
 * Publication du travail de la session sur une <b>branche dédiée</b> (F-31 / SF-31-04, ADR-015).
 *
 * <p>Le push est réalisé <b>par l'agent, dans la sandbox</b>, via le proxy git du fournisseur : c'est
 * le seul chemin où le jeton reste hors du conteneur. Reconstruire un commit depuis la Gateway
 * (blobs/arbres/commits/refs via l'API REST) réimplémenterait ce que le fournisseur fait déjà —
 * contraire à Provider-First.</p>
 *
 * <p><b>On vérifie, on ne croit pas</b> : après le tour, l'existence de la branche est constatée
 * auprès de GitHub. Un agent peut répondre « poussé » sans l'avoir fait — un jeton en lecture seule
 * échoue au {@code push}, et la sortie peut se perdre dans une longue trace.</p>
 *
 * <p><b>Jamais sur la branche de base</b> : le travail d'un agent arrive sur une branche que
 * l'utilisateur relit avant de fusionner.</p>
 */
@Service
public class GitPushService {

    private static final Logger log = LoggerFactory.getLogger(GitPushService.class);

    /** Préfixe des branches créées par l'Atelier : reconnaissable d'un coup d'œil sur le dépôt. */
    private static final String BRANCH_PREFIX = "claude/atelier-";

    private static final DateTimeFormatter BRANCH_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC);

    private static final String DEFAULT_COMMIT_MESSAGE = "Travaux de l'Atelier Claude Gateway";

    private final WorkspaceService workspaceService;
    private final GitWorkspaceService gitWorkspaceService;
    private final AtelierSessionService sessionService;
    private final GitTokenService gitTokenService;
    private final GitHubClient gitHubClient;

    public GitPushService(WorkspaceService workspaceService, GitWorkspaceService gitWorkspaceService,
            AtelierSessionService sessionService, GitTokenService gitTokenService,
            GitHubClient gitHubClient) {
        this.workspaceService = workspaceService;
        this.gitWorkspaceService = gitWorkspaceService;
        this.sessionService = sessionService;
        this.gitTokenService = gitTokenService;
        this.gitHubClient = gitHubClient;
    }

    /**
     * Publie le travail de la session en cours sur une branche dédiée.
     *
     * @param userId        propriétaire (isolation multi-tenant)
     * @param workspaceId   workspace cible
     * @param requestedName branche demandée, ou {@code null} pour un nom horodaté
     * @param commitMessage message de commit, ou {@code null} pour le libellé par défaut
     * @return la branche, l'état réellement constaté, le lien de comparaison et le compte rendu
     * @throws fr.claudegateway.atelier.WorkspaceNotFoundException            workspace non possédé
     * @throws GitWorkspaceRequiredException                                  projet d'archive
     * @throws InvalidGitBranchException                                      branche invalide, ou branche de base
     * @throws GitTokenMissingException                                       aucun jeton enregistré
     * @throws fr.claudegateway.atelier.agent.NoActiveSessionException        aucune session en cours
     * @throws fr.claudegateway.git.GitHubUnavailableException                GitHub injoignable à la vérification
     */
    public GitPushResponse push(UUID userId, UUID workspaceId, String requestedName,
            String commitMessage) {
        // 1. Isolation d'abord : un workspace d'un autre utilisateur est introuvable.
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);

        // 2. Un projet d'archive n'a pas de dépôt où publier.
        if (!workspace.isGit()) {
            throw new GitWorkspaceRequiredException(
                    "Ce projet n'est pas adossé à un dépôt Git : il n'y a rien à publier.");
        }

        // 3. Branche validée AVANT tout appel : une branche invalide, ou la branche de base, ne doit
        // jamais atteindre la sandbox.
        String branch = resolveBranch(requestedName, workspace.getGitBranch());

        // 4. Le jeton doit exister : sans lui, la vérification d'après-coup serait impossible et on
        // annoncerait un résultat qu'on ne peut pas constater.
        String token = gitTokenService.resolveToken(userId)
                .orElseThrow(() -> new GitTokenMissingException(
                        "Aucun jeton GitHub enregistré : ajoutez-en un dans vos réglages."));

        // 5. Le tour de publication, dans la session EXISTANTE (jamais une session neuve : elle
        // repartirait d'un clone vierge et publierait une branche identique à la base).
        AtelierSessionResult result = sessionService.runInExistingSession(userId, workspaceId,
                instruction(branch, commitMessage, workspace.getGitBranch()));

        // 6. On constate. Ce que l'agent déclare ne suffit pas.
        boolean pushed = gitHubClient.branchExists(token, workspace.getGitOwner(), workspace.getGitRepo(),
                branch);
        log.info("Publication sur branche demandée par l'utilisateur {} : {} (poussée={})", userId,
                branch, pushed);

        return new GitPushResponse(branch, pushed,
                pushed ? compareUrl(workspace, branch) : null, result.reply());
    }

    /**
     * Branche de publication : celle demandée, ou un nom horodaté. Refuse la branche de base — le
     * travail d'un agent n'atterrit jamais directement sur la branche par défaut (ADR-015).
     */
    private static String resolveBranch(String requestedName, String baseBranch) {
        String candidate = requestedName == null || requestedName.isBlank()
                ? BRANCH_PREFIX + BRANCH_STAMP.format(OffsetDateTime.now(ZoneOffset.UTC))
                : requestedName;
        String branch = GitRepositoryRef.requireValidBranch(candidate);
        if (branch.equals(baseBranch)) {
            throw new InvalidGitBranchException(
                    "Le travail se publie sur une branche dédiée, jamais sur « " + baseBranch + " ».");
        }
        return branch;
    }

    /**
     * Instruction de publication envoyée à l'agent. Elle décrit le résultat attendu et les garde-fous
     * ; elle ne contient <b>aucun secret</b> — le jeton est injecté par le proxy git, hors du
     * conteneur.
     */
    private static String instruction(String branch, String commitMessage, String baseBranch) {
        String message = commitMessage == null || commitMessage.isBlank()
                ? DEFAULT_COMMIT_MESSAGE
                : commitMessage.trim();
        return """
                Publie le travail en cours sur la branche dédiée « %s » du dépôt monté.

                Marche à suivre, dans cet ordre :
                1. place-toi sur la branche « %s » (crée-la si elle n'existe pas) ;
                2. indexe toutes les modifications ;
                3. crée un commit intitulé : %s ;
                4. pousse la branche sur le dépôt distant, en suivi.

                Ne pousse jamais sur « %s ». S'il n'y a rien à commiter, ou si le push échoue, dis-le
                explicitement avec le message d'erreur exact, sans le reformuler.
                """.formatted(branch, branch, message, baseBranch);
    }

    /**
     * Lien d'ouverture de pull request. Rendre l'utilisateur maître de l'ouverture est le
     * comportement de cette version : la création automatique relève de SF-31-05.
     */
    private static String compareUrl(Workspace workspace, String branch) {
        return "https://github.com/" + workspace.getGitOwner() + "/" + workspace.getGitRepo()
                + "/compare/" + workspace.getGitBranch() + "..." + branch + "?expand=1";
    }
}
