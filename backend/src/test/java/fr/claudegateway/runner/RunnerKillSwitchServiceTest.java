package fr.claudegateway.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.RunnerKillSwitchService.KillResult;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;

/**
 * Coupe-circuit et révocation immédiate (F-38 / SF-38-08).
 *
 * <p>Le défaut corrigé ici est précis : révoquer un jeton posait {@code revoked_at} mais laissait la
 * socket ouverte — le runner continuait de servir les appels d'une connexion pourtant retirée. Une
 * révocation qui ne révoque rien.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunnerKillSwitchServiceTest {

    @Mock private RunnerTokenService tokenService;
    @Mock private WorkspaceService workspaceService;
    @Mock private RunnerCallDispatcher dispatcher;
    @Mock private RunnerAuditService auditService;

    private RunnerKillSwitchService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RunnerKillSwitchService(tokenService, workspaceService, dispatcher, auditService);
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        when(workspaceService.setExecutionTarget(userId, workspaceId, WorkspaceExecutionTarget.SANDBOX))
                .thenReturn(workspace);
    }

    private RunnerToken token(UUID id, boolean revoked) {
        return RunnerToken.builder()
                .id(id).userId(userId).workspaceId(workspaceId).tokenHash("h" + id)
                .expiresAt(OffsetDateTime.now().plusDays(1))
                .revokedAt(revoked ? OffsetDateTime.now() : null)
                .build();
    }

    @Test
    void revokingTheTokenThatHoldsTheConnectionCutsItImmediately() {
        UUID tokenId = UUID.randomUUID();
        when(dispatcher.localTokenId(workspaceId)).thenReturn(Optional.of(tokenId));

        service.revokeToken(userId, workspaceId, tokenId);

        verify(tokenService).revoke(userId, workspaceId, tokenId);
        verify(dispatcher).disconnect(eq(workspaceId), anyString());
    }

    @Test
    void revokingAnotherTokenLeavesTheLiveConnectionAlone() {
        when(dispatcher.localTokenId(workspaceId)).thenReturn(Optional.of(UUID.randomUUID()));

        service.revokeToken(userId, workspaceId, UUID.randomUUID());

        verify(tokenService).revoke(eq(userId), eq(workspaceId), any());
        verify(dispatcher, never()).disconnect(any(), anyString());
    }

    @Test
    void theKillSwitchRevokesEverythingCutsTheLinkAndFallsBackToSandbox() {
        UUID live = UUID.randomUUID();
        UUID alreadyRevoked = UUID.randomUUID();
        when(tokenService.list(userId, workspaceId))
                .thenReturn(List.of(token(live, false), token(alreadyRevoked, true)));
        when(dispatcher.disconnect(eq(workspaceId), anyString())).thenReturn(true);

        KillResult result = service.kill(userId, workspaceId);

        verify(tokenService).revoke(userId, workspaceId, live);
        verify(tokenService, never()).revoke(userId, workspaceId, alreadyRevoked);
        verify(dispatcher).disconnect(eq(workspaceId), anyString());
        verify(workspaceService).setExecutionTarget(userId, workspaceId,
                WorkspaceExecutionTarget.SANDBOX);
        verify(auditService).recordKillSwitch(userId, workspaceId, 1);
        assertThat(result.revokedTokens()).isEqualTo(1);
        assertThat(result.disconnected()).isTrue();
        assertThat(result.executionTarget()).isEqualTo(WorkspaceExecutionTarget.SANDBOX);
    }

    @Test
    void cuttingAnAlreadyCutLinkIsNotAnError() {
        when(tokenService.list(userId, workspaceId)).thenReturn(List.of());
        when(dispatcher.disconnect(eq(workspaceId), anyString())).thenReturn(false);

        KillResult result = service.kill(userId, workspaceId);

        assertThat(result.revokedTokens()).isZero();
        assertThat(result.disconnected()).isFalse();
        assertThat(result.executionTarget()).isEqualTo(WorkspaceExecutionTarget.SANDBOX);
    }
}
