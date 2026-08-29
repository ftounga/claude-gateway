package fr.claudegateway.runner;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.runner.RunnerPairingService.PairingCode;
import fr.claudegateway.runner.dto.PairingCodeResponse;
import fr.claudegateway.runner.dto.RunnerStatusResponse;
import fr.claudegateway.runner.dto.RunnerTokenResponse;

/**
 * Gestion des runners d'un workspace par son propriétaire (F-38 / SF-38-01) : génération d'un code
 * d'appairage, listing et révocation des jetons. Endpoints <b>JWT</b> (chaîne principale), gardés
 * par l'accès Atelier (Gold/ADMIN). L'identité vient du {@link CurrentUser}, jamais d'un paramètre.
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/runner")
public class RunnerManagementController {

    private final RunnerPairingService pairingService;
    private final RunnerTokenService tokenService;
    private final RunnerStatusService statusService;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public RunnerManagementController(RunnerPairingService pairingService,
            RunnerTokenService tokenService, RunnerStatusService statusService,
            AtelierAccessService atelierAccess, CurrentUser currentUser) {
        this.pairingService = pairingService;
        this.tokenService = tokenService;
        this.statusService = statusService;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /** Génère un code d'appairage à usage unique pour ce workspace. */
    @PostMapping("/pairing-code")
    public PairingCodeResponse createPairingCode(@PathVariable UUID workspaceId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        PairingCode code = pairingService.createPairingCode(userId, workspaceId);
        return new PairingCodeResponse(code.code(), code.expiresAt());
    }

    /** Liste les jetons runner de ce workspace (métadonnées seulement). */
    @GetMapping("/tokens")
    public List<RunnerTokenResponse> listTokens(@PathVariable UUID workspaceId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        return tokenService.list(userId, workspaceId).stream()
                .map(RunnerTokenResponse::from)
                .toList();
    }

    /** État « runner connecté / déconnecté » de ce workspace. */
    @GetMapping("/status")
    public RunnerStatusResponse status(@PathVariable UUID workspaceId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        return RunnerStatusResponse.from(statusService.status(userId, workspaceId));
    }

    /** Révoque un jeton runner. Idempotent. */
    @DeleteMapping("/tokens/{tokenId}")
    public ResponseEntity<Void> revokeToken(@PathVariable UUID workspaceId, @PathVariable UUID tokenId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        tokenService.revoke(userId, workspaceId, tokenId);
        return ResponseEntity.noContent().build();
    }
}
