package fr.claudegateway.atelier.git;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.git.GitCommitResult;
import fr.claudegateway.git.GitFileEdit;
import fr.claudegateway.git.GitHubClient;
import fr.claudegateway.git.GitPullRequest;
import fr.claudegateway.git.GitTokenMissingException;
import fr.claudegateway.git.GitTokenService;

/**
 * Publication des modifications faites <b>par l'utilisateur lui-même</b> sur un projet Git
 * (F-31 / SF-31-08) : un commit atomique sur une branche dédiée, via l'API GitHub.
 *
 * <p><b>Pourquoi passer par l'API et non par la sandbox.</b> L'explorateur d'un projet Git lit déjà
 * l'API GitHub (SF-31-03) pour ne pas payer de temps de bac à sable à chaque ouverture d'écran. On
 * écrit donc au même endroit : le dépôt reste la seule source de vérité, et éditer un fichier
 * n'exige <b>aucune session ouverte</b>. Écrire dans le stockage — ce que SF-31-03 a refusé — aurait
 * créé deux vérités ; écrire dans le clone aurait imposé d'ouvrir une session facturée pour changer
 * une ligne de texte.</p>
 *
 * <p><b>Jamais la branche par défaut.</b> Le refus est posé ici, avant tout appel d'écriture : une
 * publication directe sur {@code master} n'est pas un cas d'usage, c'est un accident.</p>
 */
@Service
public class GitCommitService {

    private static final Logger log = LoggerFactory.getLogger(GitCommitService.class);

    private final WorkspaceService workspaceService;
    private final GitTokenService gitTokenService;
    private final GitHubClient gitHubClient;

    public GitCommitService(WorkspaceService workspaceService, GitTokenService gitTokenService,
            GitHubClient gitHubClient) {
        this.workspaceService = workspaceService;
        this.gitTokenService = gitTokenService;
        this.gitHubClient = gitHubClient;
    }

    /**
     * Publie les fichiers donnés en un commit sur {@code branch}.
     *
     * @param userId      propriétaire (isolation)
     * @param workspaceId projet visé
     * @param branch      branche cible, jamais la branche par défaut du projet
     * @param message     message de commit
     * @param files       fichiers à publier (chemins déjà validés par le DTO)
     * @return la branche, le commit, et la pull request ouverte s'il en existe une
     */
    public CommitPublication commit(UUID userId, UUID workspaceId, String branch, String message,
            List<GitFileEdit> files) {
        // 1. Isolation d'abord : le workspace d'un autre utilisateur est introuvable.
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);

        // 2. Un projet d'archive n'a pas de dépôt où publier.
        if (!workspace.isGit()) {
            throw new GitWorkspaceRequiredException(
                    "Ce projet n'est pas adossé à un dépôt Git : il n'y a rien à publier.");
        }

        // 3. Branche validée avant tout appel réseau.
        String target = GitRepositoryRef.requireValidBranch(branch);
        String base = workspace.getGitBranch();

        // 4. Le jeton : sans lui, rien n'est publiable.
        String token = gitTokenService.resolveToken(userId)
                .orElseThrow(() -> new GitTokenMissingException(
                        "Aucun jeton GitHub enregistré : ajoutez-en un dans vos réglages."));

        // 5. Le refus porte sur la branche PAR DÉFAUT DU DÉPÔT, pas sur celle du projet (SF-31-10).
        // Tant qu'un projet suivait forcément `master`, les deux se confondaient. Depuis que le projet
        // peut suivre une branche de travail, refuser « la branche du projet » interdirait de commiter
        // sur sa propre branche — exactement ce qu'on veut faire.
        String defaultBranch = gitHubClient
                .getRepository(token, workspace.getGitOwner(), workspace.getGitRepo())
                .defaultBranch();
        if (target.equals(defaultBranch)) {
            throw new GitDefaultBranchRefusedException(
                    "Publier directement sur « " + defaultBranch
                            + " » n'est pas permis : choisissez une autre branche.");
        }

        GitCommitResult result = gitHubClient.commitFiles(token, workspace.getGitOwner(),
                workspace.getGitRepo(), base, target, message, files);
        log.info("Commit publié depuis l'écran : workspace={} branche={} fichiers={}",
                workspaceId, target, files.size());

        Optional<GitPullRequest> pullRequest =
                gitHubClient.findOpenPullRequest(token, workspace.getGitOwner(), workspace.getGitRepo(), target);
        return new CommitPublication(result, compareUrl(workspace, base, target), pullRequest.orElse(null));
    }

    /**
     * Lien de comparaison, repli constant de F-31 : même sans pull request ouverte, l'utilisateur a
     * une adresse où voir ce qu'il vient de publier.
     */
    private static String compareUrl(Workspace workspace, String base, String branch) {
        return "https://github.com/" + workspace.getGitOwner() + "/" + workspace.getGitRepo()
                + "/compare/" + base + "..." + branch + "?expand=1";
    }

    /**
     * Ce qui est rendu à l'écran après publication.
     *
     * @param result      branche et commit créés
     * @param compareUrl  lien de comparaison sur GitHub
     * @param pullRequest pull request déjà ouverte sur cette branche, ou {@code null}
     */
    public record CommitPublication(GitCommitResult result, String compareUrl, GitPullRequest pullRequest) {
    }
}
