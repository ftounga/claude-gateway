package fr.claudegateway.atelier.git;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.git.GitHubClient;
import fr.claudegateway.git.GitTokenMissingException;
import fr.claudegateway.git.GitTokenService;

/**
 * Choix et création de la branche d'un projet Git (F-31 / SF-31-10).
 *
 * <p><b>Pourquoi l'explorateur en a besoin.</b> Le terminal fait ce qu'on lui demande — Claude change
 * de branche, en crée, commite. L'explorateur, lui, affichait une branche figée : impossible de voir
 * ce qu'on venait de publier sans aller sur GitHub.</p>
 *
 * <p>Changer de branche <b>ne touche pas à la session en cours</b> : elle a monté l'ancienne branche
 * à son ouverture et continue dessus. La réinitialiser serait détruire son environnement sans qu'on
 * l'ait demandé — l'écran avertit, l'utilisateur décide.</p>
 */
@Service
public class GitBranchService {

    private static final Logger log = LoggerFactory.getLogger(GitBranchService.class);

    private final WorkspaceService workspaceService;
    private final WorkspaceRepository workspaceRepository;
    private final GitTokenService gitTokenService;
    private final GitHubClient gitHubClient;

    public GitBranchService(WorkspaceService workspaceService, WorkspaceRepository workspaceRepository,
            GitTokenService gitTokenService, GitHubClient gitHubClient) {
        this.workspaceService = workspaceService;
        this.workspaceRepository = workspaceRepository;
        this.gitTokenService = gitTokenService;
        this.gitHubClient = gitHubClient;
    }

    /**
     * Branches du dépôt et branche par défaut.
     *
     * @param userId      propriétaire (isolation)
     * @param workspaceId projet visé
     * @return la liste, la branche courante du projet et celle par défaut du dépôt
     */
    public Branches list(UUID userId, UUID workspaceId) {
        Workspace workspace = requireGitWorkspace(userId, workspaceId);
        String token = tokenOf(userId);
        List<String> branches = gitHubClient.listBranches(token, workspace.getGitOwner(), workspace.getGitRepo());
        String defaultBranch = gitHubClient
                .getRepository(token, workspace.getGitOwner(), workspace.getGitRepo())
                .defaultBranch();
        return new Branches(branches, workspace.getGitBranch(), defaultBranch);
    }

    /**
     * Place le projet sur une branche existante.
     *
     * <p>L'existence est <b>constatée auprès de GitHub</b> avant d'écrire : un projet qui pointe une
     * branche disparue afficherait une arborescence vide sans dire pourquoi.</p>
     */
    @Transactional
    public Workspace switchTo(UUID userId, UUID workspaceId, String branch) {
        Workspace workspace = requireGitWorkspace(userId, workspaceId);
        String target = GitRepositoryRef.requireValidBranch(branch);
        if (!gitHubClient.branchExists(tokenOf(userId), workspace.getGitOwner(), workspace.getGitRepo(), target)) {
            throw new GitBranchUnknownException("La branche « " + target + " » n'existe pas sur ce dépôt.");
        }
        workspace.setGitBranch(target);
        log.info("Projet {} placé sur la branche {}", workspaceId, target);
        return workspaceRepository.save(workspace);
    }

    /**
     * Crée une branche depuis celle du projet, puis s'y place.
     *
     * @throws GitBranchExistsException si la branche existe déjà — on ne réécrit jamais une référence
     */
    @Transactional
    public Workspace createAndSwitch(UUID userId, UUID workspaceId, String branch) {
        Workspace workspace = requireGitWorkspace(userId, workspaceId);
        String target = GitRepositoryRef.requireValidBranch(branch);
        String token = tokenOf(userId);
        if (gitHubClient.branchExists(token, workspace.getGitOwner(), workspace.getGitRepo(), target)) {
            throw new GitBranchExistsException("La branche « " + target + " » existe déjà.");
        }
        gitHubClient.createBranch(token, workspace.getGitOwner(), workspace.getGitRepo(),
                workspace.getGitBranch(), target);
        workspace.setGitBranch(target);
        log.info("Branche {} créée depuis {} pour le projet {}", target, workspace.getGitBranch(), workspaceId);
        return workspaceRepository.save(workspace);
    }

    private Workspace requireGitWorkspace(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        if (!workspace.isGit()) {
            throw new GitWorkspaceRequiredException("Ce projet n'est pas adossé à un dépôt Git.");
        }
        return workspace;
    }

    private String tokenOf(UUID userId) {
        return gitTokenService.resolveToken(userId)
                .orElseThrow(() -> new GitTokenMissingException(
                        "Aucun jeton GitHub enregistré : ajoutez-en un dans vos réglages."));
    }

    /**
     * @param branches      branches du dépôt, triées
     * @param current       branche du projet
     * @param defaultBranch branche par défaut du dépôt — celle sur laquelle publier reste interdit
     */
    public record Branches(List<String> branches, String current, String defaultBranch) {
    }
}
