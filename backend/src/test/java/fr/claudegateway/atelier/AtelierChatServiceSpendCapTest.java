package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.quota.UsageSnapshot;

/**
 * F-183 / SF-183-03 — le budget en dollars d'une exécution de routine, dans la boucle : coupe nette avant
 * l'appel qui franchirait le plafond, première itération toujours permise, tour non armé strictement
 * inchangé, et <b>rien n'est dégradé</b> (même modèle, même raisonnement).
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceSpendCapTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private fr.claudegateway.runner.exec.RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private fr.claudegateway.runner.exec.RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        lenient().when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        lenient().when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        lenient().when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        lenient().when(workspaceService.tree(any(), any())).thenReturn(List.of());
        lenient().when(workspaceService.readFile(any(), any(), any())).thenReturn("contenu");
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        lenient().when(quotaService.currentUsage(userId))
                .thenReturn(new UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        // Un centime par token, toutes natures confondues : 10 tokens = 0,10 $.
        lenient().when(quotaService.costOf(any(TurnTokens.class), any(), any())).thenAnswer(inv -> {
            TurnTokens t = inv.getArgument(0);
            long total = t.inputTokens() + t.outputTokens() + t.cacheReadTokens() + t.cacheWriteTokens();
            return BigDecimal.valueOf(total).movePointLeft(2);
        });
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, true));
    }

    @Test
    void anArmedTurnStopsBeforeTheCallThatWouldCrossTheCap() {
        service.capSpend(userId, workspaceId, new BigDecimal("0.25"));
        // Chaque itération coûte 0,10 $ : deux passent (0,10 puis 0,20) ; la troisième, projetée à
        // 0,30 $, est refusée AVANT l'appel.
        for (int i = 0; i < 5; i++) {
            agentProvider.enqueueToolCallCosting("read_file", 5, 5);
        }

        AtelierChatResult result = service.chat(userId, workspaceId, "fais le point");

        assertThat(result.budgetReached()).isTrue();
        assertThat(result.reply()).startsWith("INCOMPLET").contains("0,23 €");
        assertThat(agentProvider.remaining()).isEqualTo(3);
        service.releaseSpendCap(userId, workspaceId);
    }

    @Test
    void theFirstIterationAlwaysRuns() {
        service.capSpend(userId, workspaceId, new BigDecimal("0.01"));
        agentProvider.enqueueToolCallCosting("read_file", 5, 5);
        agentProvider.enqueueToolCallCosting("read_file", 5, 5);

        AtelierChatResult result = service.chat(userId, workspaceId, "fais le point");

        assertThat(result.budgetReached()).isTrue();
        assertThat(agentProvider.remaining()).isEqualTo(1);
    }

    @Test
    void anUnarmedTurnIsUntouchedAndNothingIsDegraded() {
        agentProvider.enqueueToolCallCosting("read_file", 5, 5);
        agentProvider.enqueueToolCallCosting("read_file", 5, 5);
        agentProvider.enqueueFinal("Voilà.");
        AtelierChatResult free = service.chat(userId, workspaceId, "fais le point");
        assertThat(free.reply()).isEqualTo("Voilà.");
        assertThat(free.budgetReached()).isFalse();
        String freeModel = agentProvider.lastRequest.model();
        List<?> freeReasoning = List.copyOf(agentProvider.reasoningSnapshots);

        // Même scénario, armé d'un plafond confortable : même réponse, même modèle, même raisonnement.
        agentProvider.reasoningSnapshots.clear();
        service.capSpend(userId, workspaceId, new BigDecimal("5.00"));
        agentProvider.enqueueToolCallCosting("read_file", 5, 5);
        agentProvider.enqueueToolCallCosting("read_file", 5, 5);
        agentProvider.enqueueFinal("Voilà.");
        AtelierChatResult armed = service.chat(userId, workspaceId, "fais le point");
        service.releaseSpendCap(userId, workspaceId);

        assertThat(armed.reply()).isEqualTo("Voilà.");
        assertThat(armed.budgetReached()).isFalse();
        assertThat(agentProvider.lastRequest.model()).isEqualTo(freeModel);
        assertThat(agentProvider.reasoningSnapshots).isEqualTo(freeReasoning);
    }

    @Test
    void aMutedTurnDoesNotSendTheGenericEndNotification() {
        fr.claudegateway.push.PushNotificationService push =
                org.mockito.Mockito.mock(fr.claudegateway.push.PushNotificationService.class);
        service.setPushNotificationService(push);
        agentProvider.enqueueFinal("Voilà.");
        service.chatStreaming(userId, workspaceId, "x", AtelierProgressListener.NOOP);
        org.mockito.Mockito.verify(push).notify(userId, workspaceId, fr.claudegateway.push.PushEvent.TURN_DONE);

        org.mockito.Mockito.clearInvocations(push);
        service.muteOutcomePush(userId, workspaceId);
        agentProvider.enqueueFinal("Voilà.");
        service.chatStreaming(userId, workspaceId, "x", AtelierProgressListener.NOOP);
        org.mockito.Mockito.verifyNoInteractions(push);

        service.unmuteOutcomePush(userId, workspaceId);
        agentProvider.enqueueFinal("Voilà.");
        service.chatStreaming(userId, workspaceId, "x", AtelierProgressListener.NOOP);
        org.mockito.Mockito.verify(push).notify(userId, workspaceId, fr.claudegateway.push.PushEvent.TURN_DONE);
    }

    @Test
    void releasedCapNoLongerApplies() {
        service.capSpend(userId, workspaceId, new BigDecimal("0.15"));
        service.releaseSpendCap(userId, workspaceId);
        for (int i = 0; i < 3; i++) {
            agentProvider.enqueueToolCallCosting("read_file", 5, 5);
        }
        agentProvider.enqueueFinal("Fini.");
        assertThat(service.chat(userId, workspaceId, "x").reply()).isEqualTo("Fini.");
    }
}
