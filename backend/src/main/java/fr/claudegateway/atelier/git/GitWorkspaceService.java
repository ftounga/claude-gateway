package fr.claudegateway.atelier.git;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.git.GitHubClient;
import fr.claudegateway.git.GitHubRepository;
import fr.claudegateway.git.GitTokenMissingException;
import fr.claudegateway.git.GitTokenService;

/**
 * Ouverture d'un projet d'Atelier sur un dépôt GitHub (F-31 / SF-31-02, ADR-015).
 *
 * <p>L'ordre des vérifications est délibéré et se lit de haut en bas :</p>
 * <ol>
 *   <li><b>URL</b> — validée hors-ligne : une URL malformée est refusée sans appel réseau ;</li>
 *   <li><b>jeton</b> — sans jeton enregistré, rien n'est tenté (aucun appel, aucune écriture) ;</li>
 *   <li><b>accès au dépôt</b> — confirmé auprès de GitHub, ce qui donne aussi la branche par défaut ;</li>
 *   <li><b>création</b> — le workspace n'est écrit qu'une fois les trois précédents verts.</li>
 * </ol>
 *
 * <p>Un workspace créé sur un dépôt inaccessible ne se manifesterait qu'au premier message, loin du
 * geste qui l'a causé : c'est exactement l'erreur que cet ordre supprime.</p>
 *
 * <p><b>Isolation multi-tenant</b> : le {@code userId} vient du {@code SecurityContext} ; le jeton
 * résolu est celui de cet utilisateur, jamais un autre. Le jeton n'est ni journalisé, ni renvoyé.</p>
 */
@Service
public class GitWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(GitWorkspaceService.class);

    private final WorkspaceService workspaceService;
    private final GitTokenService gitTokenService;
    private final GitHubClient gitHubClient;

    public GitWorkspaceService(WorkspaceService workspaceService, GitTokenService gitTokenService,
            GitHubClient gitHubClient) {
        this.workspaceService = workspaceService;
        this.gitTokenService = gitTokenService;
        this.gitHubClient = gitHubClient;
    }

    /**
     * Crée un workspace de source {@code GIT}.
     *
     * @param userId  propriétaire (isolation multi-tenant)
     * @param repoUrl URL du dépôt saisie par l'utilisateur
     * @param branch  branche demandée, ou {@code null} pour la branche par défaut du dépôt
     * @param name    nom du projet, ou {@code null} pour le nom du dépôt
     * @return le workspace créé
     * @throws fr.claudegateway.git.InvalidGitRepositoryException URL invalide, ou dépôt introuvable /
     *                                                            hors de portée du jeton
     * @throws GitTokenMissingException                           aucun jeton GitHub enregistré
     * @throws fr.claudegateway.git.InvalidGitTokenException      jeton refusé par GitHub
     * @throws fr.claudegateway.git.GitHubUnavailableException    GitHub injoignable
     */
    public Workspace create(UUID userId, String repoUrl, String branch, String name) {
        GitRepositoryRef ref = GitRepositoryRef.parse(repoUrl);
        String requestedBranch = branch == null || branch.isBlank()
                ? null
                : GitRepositoryRef.requireValidBranch(branch);

        String token = gitTokenService.resolveToken(userId)
                .orElseThrow(() -> new GitTokenMissingException(
                        "Enregistrez d'abord un jeton GitHub dans vos réglages pour ouvrir un dépôt."));

        GitHubRepository repository = gitHubClient.getRepository(token, ref.owner(), ref.repo());
        String mountedBranch = requestedBranch == null ? repository.defaultBranch() : requestedBranch;

        Workspace workspace = workspaceService.createFromGit(userId, name, ref.cloneUrl(), ref.owner(),
                ref.repo(), mountedBranch);
        log.info("Workspace Git ouvert pour l'utilisateur {} sur {} ({})", userId, ref.fullName(),
                mountedBranch);
        return workspace;
    }
}
