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
 * Coupe-circuit et révocation immédiate (F-38 / SF-38-08), désormais à l'échelle du <b>poste</b>
 * (F-48 / SF-48-01).
 *
 * <p>Le défaut corrigé à l'origine est précis : révoquer un jeton posait {@code revoked_at} mais
 * laissait la socket ouverte — le runner continuait de servir les appels d'une connexion pourtant
 * retirée. Une révocation qui ne révoque rien.</p>
 *
 * <p>F-48 en ajoute un second, de même nature : on ne coupe pas un dossier, on coupe une machine.
 * Ne ramener qu'un projet au bac à sable laisserait les autres projets du poste pointer vers un
 * runner mort.</p>
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
    private final UUID hostId = UUID.randomUUID();
    private final UUID firstProject = UUID.randomUUID();
    private final UUID secondProject = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RunnerKillSwitchService(tokenService, workspaceService, dispatcher, auditService);
    }

    /** Deux projets sous le même poste : un en cible RUNNER, l'autre déjà au bac à sable. */
    private void givenTwoProjectsOnTheHost() {
        Workspace onTheMachine = new Workspace();
        onTheMachine.setId(firstProject);
        onTheMachine.setUserId(userId);
        onTheMachine.setHostId(hostId);
        onTheMachine.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        Workspace alreadySandbox = new Workspace();
        alreadySandbox.setId(secondProject);
        alreadySandbox.setUserId(userId);
        alreadySandbox.setHostId(hostId);
        alreadySandbox.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        when(workspaceService.listByHost(userId, hostId))
                .thenReturn(List.of(onTheMachine, alreadySandbox));
    }

    private RunnerToken token(UUID id, boolean revoked) {
        return RunnerToken.builder()
                .id(id).userId(userId).hostId(hostId).tokenHash("h" + id)
                .expiresAt(OffsetDateTime.now().plusDays(1))
                .revokedAt(revoked ? OffsetDateTime.now() : null)
                .build();
    }

    @Test
    void revokingTheTokenThatHoldsTheConnectionCutsItImmediately() {
        UUID tokenId = UUID.randomUUID();
        when(dispatcher.localTokenId(hostId)).thenReturn(Optional.of(tokenId));

        service.revokeToken(userId, hostId, tokenId);

        verify(tokenService).revoke(userId, hostId, tokenId);
        verify(dispatcher).disconnect(eq(hostId), anyString());
    }

    @Test
    void revokingAnotherTokenLeavesTheLiveConnectionAlone() {
        when(dispatcher.localTokenId(hostId)).thenReturn(Optional.of(UUID.randomUUID()));

        service.revokeToken(userId, hostId, UUID.randomUUID());

        verify(tokenService).revoke(eq(userId), eq(hostId), any());
        verify(dispatcher, never()).disconnect(any(), anyString());
    }

    @Test
    void theKillSwitchRevokesEverythingCutsTheLinkAndBringsEveryProjectBackToTheSandbox() {
        UUID live = UUID.randomUUID();
        UUID alreadyRevoked = UUID.randomUUID();
        when(tokenService.list(userId, hostId))
                .thenReturn(List.of(token(live, false), token(alreadyRevoked, true)));
        when(dispatcher.disconnect(eq(hostId), anyString())).thenReturn(true);
        givenTwoProjectsOnTheHost();

        KillResult result = service.kill(userId, hostId);

        verify(tokenService).revoke(userId, hostId, live);
        verify(tokenService, never()).revoke(userId, hostId, alreadyRevoked);
        verify(dispatcher).disconnect(eq(hostId), anyString());
        // Seuls les projets qui pointaient encore vers la machine sont ramenés : les autres y
        // étaient déjà, et les réécrire serait une écriture pour rien.
        verify(workspaceService).setExecutionTarget(userId, firstProject,
                WorkspaceExecutionTarget.SANDBOX);
        verify(workspaceService, never()).setExecutionTarget(userId, secondProject,
                WorkspaceExecutionTarget.SANDBOX);
        verify(auditService).recordKillSwitch(userId, hostId, 1);
        assertThat(result.revokedTokens()).isEqualTo(1);
        assertThat(result.disconnected()).isTrue();
        assertThat(result.workspacesReturned()).isEqualTo(1);
    }

    @Test
    void cuttingAnAlreadyCutLinkIsNotAnError() {
        when(tokenService.list(userId, hostId)).thenReturn(List.of());
        when(dispatcher.disconnect(eq(hostId), anyString())).thenReturn(false);
        when(workspaceService.listByHost(userId, hostId)).thenReturn(List.of());

        KillResult result = service.kill(userId, hostId);

        assertThat(result.revokedTokens()).isZero();
        assertThat(result.disconnected()).isFalse();
        assertThat(result.workspacesReturned()).isZero();
    }
}
