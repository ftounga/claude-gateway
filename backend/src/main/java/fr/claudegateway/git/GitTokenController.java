package fr.claudegateway.git;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.git.dto.GitTokenStatusResponse;
import fr.claudegateway.git.dto.SaveGitTokenRequest;
import jakarta.validation.Valid;

/**
 * API de gestion du jeton GitHub de l'utilisateur courant (F-31 / SF-31-01) sous
 * {@code /api/user/git-token} — chemin aligné sur {@code /api/user/api-key} (F-03).
 *
 * <p>Le controller ne porte aucune logique métier : il résout le {@code user_id} du contexte de
 * sécurité (isolation multi-tenant — aucun identifiant n'est jamais accepté du client) et délègue à
 * {@link GitTokenService}. Le jeton n'est jamais renvoyé en clair.</p>
 */
@RestController
@RequestMapping("/user/git-token")
public class GitTokenController {

    private final GitTokenService gitTokenService;
    private final CurrentUser currentUser;

    public GitTokenController(GitTokenService gitTokenService, CurrentUser currentUser) {
        this.gitTokenService = gitTokenService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public GitTokenStatusResponse status() {
        return gitTokenService.getStatus(currentUser.requireId());
    }

    @PostMapping
    public GitTokenStatusResponse save(@Valid @RequestBody SaveGitTokenRequest request) {
        return gitTokenService.saveToken(currentUser.requireId(), request.token());
    }

    @DeleteMapping
    public ResponseEntity<Void> delete() {
        gitTokenService.deleteToken(currentUser.requireId());
        return ResponseEntity.noContent().build();
    }
}
