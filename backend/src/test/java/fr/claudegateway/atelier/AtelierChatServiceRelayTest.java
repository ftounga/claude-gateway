package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.exec.NoPendingConfirmationException;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.runner.relay.RelayInterruptTarget;
import fr.claudegateway.runner.relay.RunnerRelayBroadcaster;

/**
 * Interruption et porte de confirmation <b>entre deux pods</b> (F-38 / SF-38-13).
 *
 * <p>Le piège que ces tests verrouillent : la porte qui attend et la marque d'interruption vivent sur
 * le pod qui exécute la boucle et tient le flux SSE, pas sur celui qui héberge la socket du runner —
 * et pas nécessairement sur celui qui reçoit le clic de l'utilisateur. Sans diffusion, une
 * autorisation donnée n'atteignait personne et la commande finissait refusée au bout de 120 s.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceRelayTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private RunnerConfirmationGate confirmationGate;
    @Mock private RunnerAuditService auditService;
    @Mock private RunnerRelayBroadcaster relayBroadcaster;

    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) new StubAiAgentProvider(), byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient,
                        new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, auditService,
                relayBroadcaster,
                // Plafond d'étapes par défaut (30) : ce fichier ne teste que le relais (SF-28-19).
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null));
    }

    // ------------------------------------------------------------------- interruption

    @Test
    void interruptingActsLocallyFirstThenBroadcastsTheSameGesture() {
        service.interruptChat(userId, workspaceId);

        InOrder order = Mockito.inOrder(workspaceService, confirmationGate, runnerCallDispatcher,
                relayBroadcaster);
        order.verify(workspaceService).requireOwned(userId, workspaceId);
        order.verify(confirmationGate).cancelWorkspace(workspaceId);
        order.verify(runnerCallDispatcher).cancelWorkspace(workspaceId, "user_interrupt");
        order.verify(relayBroadcaster).broadcastInterrupt(userId, workspaceId, "user_interrupt");
    }

    @Test
    void aRelayedInterruptAppliesTheThreeGesturesInOrderWithoutOwnershipCheck() {
        // Le pod destinataire ne revérifie pas l'appartenance : elle a déjà été faite par le pod qui
        // a reçu la requête de l'utilisateur, et le userId ne sert ici que de clef de marque.
        when(confirmationGate.cancelWorkspace(workspaceId)).thenReturn(2);
        when(runnerCallDispatcher.cancelWorkspace(workspaceId, "user_interrupt")).thenReturn(1);

        RelayInterruptTarget.RelayInterruptOutcome outcome =
                service.interruptLocally(userId, workspaceId, "user_interrupt");

        assertThat(outcome.released()).isEqualTo(2);
        assertThat(outcome.cancelled()).isEqualTo(1);
        verifyNoInteractions(workspaceService);
        verifyNoInteractions(relayBroadcaster);
    }

    // -------------------------------------------------------------------- confirmation

    @Test
    void aDecisionResolvedLocallyIsNeverBroadcast() {
        service.confirmToolUse(userId, workspaceId, " toolu_1 ", true, "vas-y");

        verify(confirmationGate).resolve(userId, workspaceId, "toolu_1", true, "vas-y");
        verifyNoInteractions(relayBroadcaster);
    }

    @Test
    void aDecisionNoOneHoldsLocallyIsBroadcastAndAcceptedWhenAPeerResolves() {
        Mockito.doThrow(new NoPendingConfirmationException("rien en attente")).when(confirmationGate)
                .resolve(any(), any(), anyString(), anyBoolean(), any());
        when(relayBroadcaster.broadcastConfirm(userId, workspaceId, "toolu_1", true, null))
                .thenReturn(true);

        service.confirmToolUse(userId, workspaceId, "toolu_1", true, null);

        verify(relayBroadcaster).broadcastConfirm(userId, workspaceId, "toolu_1", true, null);
    }

    @Test
    void aDecisionNoPodHoldsKeepsThe409() {
        // Personne n'a tranché : l'erreur d'origine remonte, et la porte qui attendrait sans être
        // atteinte expirera en refus. Le silence ne vaut jamais autorisation.
        Mockito.doThrow(new NoPendingConfirmationException("rien en attente")).when(confirmationGate)
                .resolve(any(), any(), anyString(), anyBoolean(), any());
        when(relayBroadcaster.broadcastConfirm(any(), any(), anyString(), anyBoolean(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.confirmToolUse(userId, workspaceId, "toolu_1", true, null))
                .isInstanceOf(NoPendingConfirmationException.class);
    }

    @Test
    void aWorkspaceOwnedBySomeoneElseNeverReachesTheInternalNetwork() {
        // Isolation d'abord, toujours : ni porte, ni diffusion sur un projet d'autrui.
        Mockito.doThrow(new WorkspaceNotFoundException("inconnu")).when(workspaceService)
                .requireOwned(eq(userId), eq(workspaceId));

        assertThatThrownBy(() -> service.confirmToolUse(userId, workspaceId, "toolu_1", true, null))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThatThrownBy(() -> service.interruptChat(userId, workspaceId))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verifyNoInteractions(relayBroadcaster);
        verify(confirmationGate, never()).resolve(any(), any(), anyString(), anyBoolean(), any());
    }
}
