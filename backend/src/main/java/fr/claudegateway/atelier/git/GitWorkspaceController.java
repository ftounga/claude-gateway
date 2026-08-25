package fr.claudegateway.atelier.git;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.dto.WorkspaceDetailResponse;
import fr.claudegateway.atelier.git.dto.CreateGitWorkspaceRequest;
import fr.claudegateway.atelier.git.dto.CreatePullRequestRequest;
import fr.claudegateway.atelier.git.dto.GitPushRequest;
import fr.claudegateway.atelier.git.dto.GitPushResponse;
import fr.claudegateway.atelier.git.dto.PullRequestResponse;
import fr.claudegateway.auth.CurrentUser;
import jakarta.validation.Valid;

/**
 * Endpoints de l'Atelier sur dépôt Git (F-31, ADR-015). Même gating que le reste de l'Atelier
 * ({@link AtelierAccessService#requireAccess()} — offre Gold ou administrateur) et même règle
 * d'identité : l'utilisateur vient du JWT, jamais d'un paramètre client.
 */
@RestController
@RequestMapping("/workspaces")
public class GitWorkspaceController {

    private final GitWorkspaceService gitWorkspaceService;
    private final GitPushService gitPushService;
    private final GitPullRequestService gitPullRequestService;
    private final WorkspaceService workspaceService;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public GitWorkspaceController(GitWorkspaceService gitWorkspaceService, GitPushService gitPushService,
            GitPullRequestService gitPullRequestService, WorkspaceService workspaceService,
            AtelierAccessService atelierAccess, CurrentUser currentUser) {
        this.gitWorkspaceService = gitWorkspaceService;
        this.gitPushService = gitPushService;
        this.gitPullRequestService = gitPullRequestService;
        this.workspaceService = workspaceService;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /**
     * Ouvre un projet sur un dépôt GitHub. Aucun fichier n'est copié : le dépôt sera cloné dans la
     * sandbox à l'ouverture de la session (SF-31-02).
     */
    @PostMapping("/git")
    public WorkspaceDetailResponse createFromGit(@Valid @RequestBody CreateGitWorkspaceRequest request) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        Workspace workspace = gitWorkspaceService.create(
                userId, request.repoUrl(), request.branch(), request.name());
        // L'arborescence d'un workspace Git est servie par SF-31-03 : à ce stade elle est vide, et
        // annoncer autre chose serait mentir sur ce qui est disponible.
        return WorkspaceDetailResponse.from(workspace, workspaceService.tree(userId, workspace.getId()));
    }

    /**
     * Publie le travail de la session sur une branche dédiée (SF-31-04) et renvoie le lien
     * d'ouverture de pull request.
     *
     * <p>Réponse {@code 200} même quand rien n'a été poussé : le tour a eu lieu et a été facturé, et
     * son compte rendu est l'information utile — une erreur HTTP le masquerait. {@code pushed} dit ce
     * qui s'est réellement passé, constaté auprès de GitHub.</p>
     */
    @PostMapping("/{id}/git/push")
    public GitPushResponse push(@PathVariable UUID id, @Valid @RequestBody(required = false) GitPushRequest request) {
        atelierAccess.requireAccess();
        return gitPushService.push(currentUser.requireId(), id,
                request == null ? null : request.branch(),
                request == null ? null : request.message());
    }

    /**
     * Ouvre la pull request de la branche publiée vers la branche de base (SF-31-05), via l'outil
     * {@code create_pull_request} du serveur MCP GitHub.
     *
     * <p>Même règle qu'au push : réponse {@code 200} même quand rien n'a été ouvert. Le tour a eu
     * lieu et a été facturé ; {@code created} dit ce qui s'est réellement passé, constaté auprès de
     * GitHub, et {@code reply} porte la cause d'un échec.</p>
     */
    @PostMapping("/{id}/git/pull-request")
    public PullRequestResponse createPullRequest(@PathVariable UUID id,
            @Valid @RequestBody CreatePullRequestRequest request) {
        atelierAccess.requireAccess();
        return gitPullRequestService.create(currentUser.requireId(), id, request.branch(),
                request.title(), request.body());
    }
}
