package fr.claudegateway.mcp.token;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.mcp.token.dto.McpTokenDtos.CreateTokenRequest;
import fr.claudegateway.mcp.token.dto.McpTokenDtos.CreatedTokenResponse;
import fr.claudegateway.mcp.token.dto.McpTokenDtos.HostOption;
import fr.claudegateway.mcp.token.dto.McpTokenDtos.JournalEntryResponse;
import fr.claudegateway.mcp.token.dto.McpTokenDtos.TokenResponse;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * API de l'écran « IA connectées » (F-112 / SF-112-03) : jetons personnels, journal MCP, et postes
 * proposés au sélecteur d'accès poste par poste. Toutes les opérations sont cloisonnées par
 * {@code user_id} (porté par le JWT plateforme) ; jamais un {@code user_id} de paramètre.
 */
@RestController
@RequestMapping("/mcp-connections")
public class McpConnectionsController {

    private final McpPersonalTokenService tokenService;
    private final McpJournalService journalService;
    private final RunnerHostRepository hostRepository;
    private final CurrentUser currentUser;

    public McpConnectionsController(
            McpPersonalTokenService tokenService,
            McpJournalService journalService,
            RunnerHostRepository hostRepository,
            CurrentUser currentUser) {
        this.tokenService = tokenService;
        this.journalService = journalService;
        this.hostRepository = hostRepository;
        this.currentUser = currentUser;
    }

    @GetMapping("/tokens")
    public List<TokenResponse> listTokens() {
        UUID userId = currentUser.requireId();
        return tokenService.list(userId).stream().map(McpConnectionsController::toResponse).toList();
    }

    @PostMapping("/tokens")
    public ResponseEntity<CreatedTokenResponse> createToken(@RequestBody CreateTokenRequest request) {
        AuthenticatedUser user = currentUser.principal()
                .orElseThrow(() -> new IllegalStateException("Aucun utilisateur authentifié"));
        McpPersonalTokenService.CreatedToken created = tokenService.create(
                user.id(), user.role(), request.name(), request.scopes(),
                request.hostIds(), request.expiresInDays());
        CreatedTokenResponse body =
                new CreatedTokenResponse(created.secret(), toResponse(created.token()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/tokens/{id}")
    public ResponseEntity<Void> revokeToken(@PathVariable("id") UUID id) {
        tokenService.revoke(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/journal")
    public List<JournalEntryResponse> journal() {
        return journalService.recentForUser(currentUser.requireId()).stream()
                .map(e -> new JournalEntryResponse(e.getId(), e.getClient(), e.getAuthKind(),
                        e.getTool(), e.getHostId(), e.getParamsSummary(), e.getResult(),
                        e.getDurationMs(), e.getCreatedAt()))
                .toList();
    }

    @GetMapping("/hosts")
    public List<HostOption> hosts() {
        return hostRepository.findByUserIdOrderByCreatedAtDesc(currentUser.requireId()).stream()
                .map(host -> new HostOption(host.getId(), host.getName()))
                .toList();
    }

    private static TokenResponse toResponse(McpPersonalToken token) {
        boolean expired = token.getExpiresAt() != null
                && !token.getExpiresAt().isAfter(java.time.OffsetDateTime.now());
        List<String> scopes = token.getScopes() == null || token.getScopes().isBlank()
                ? List.of() : List.of(token.getScopes().trim().split(" "));
        return new TokenResponse(
                token.getId(),
                token.getName(),
                token.getTokenPrefix(),
                scopes,
                List.copyOf(token.getHostIds()),
                token.getCreatedAt(),
                token.getExpiresAt(),
                token.getLastUsedAt(),
                token.getRevokedAt() != null,
                expired);
    }
}
