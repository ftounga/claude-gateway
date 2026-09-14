package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * Compaction automatique branchée sur la boucle (F-117 / SF-117-01) : un fil long est compacté avant
 * le tour, et ce qui repart au fournisseur porte le résumé en tête + les seuls tours récents — tandis
 * que l'affichage (l'historique en base) n'est jamais touché.
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceCompactionTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private WorkspaceRepository workspaceRepository;
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
    private Workspace workspace;
    private final List<AtelierMessage> history = new ArrayList<>();
    private final List<AtelierMessage> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));
        // Seuil bas (100 tokens) et 2 messages récents gardés entiers.
        AtelierCompactionService compaction = new AtelierCompactionService(messageRepository,
                workspaceRepository, (AiAgentProvider) agentProvider,
                new AtelierCompactionProperties(true, 100, 2),
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));
        service.setCompactionService(compaction);

        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        lenient().when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(UUID.randomUUID());
            }
            saved.add(message);
            return message;
        });
        lenient().when(workspaceService.tree(any(), any())).thenReturn(List.of());
    }

    private AtelierMessage msg(String role, String content, OffsetDateTime at) {
        return AtelierMessage.builder().id(UUID.randomUUID()).workspaceId(workspaceId).userId(userId)
                .role(role).content(content).createdAt(at).build();
    }

    private String longText(String prefix) {
        return prefix + " " + "x".repeat(400);
    }

    @Test
    void aLongThreadIsCompactedAndTheTurnReplaysSummaryPlusRecentTurnsOnly() {
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(6);
        // 6 messages (3 échanges) longs : au-dessus du seuil, on garde les 2 derniers entiers.
        for (int i = 0; i < 3; i++) {
            history.add(msg("USER", longText("demande " + i), t0.plusMinutes(i * 2L)));
            history.add(msg("ASSISTANT", longText("réponse " + i), t0.plusMinutes(i * 2L + 1)));
        }
        OffsetDateTime boundary = history.get(4).getCreatedAt();
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);
        // Après compaction, le tour relit depuis la nouvelle frontière (2 derniers messages).
        when(messageRepository.findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                workspaceId, userId, boundary)).thenReturn(history.subList(4, 6));

        // Premier appel fournisseur = résumé de compaction ; second = le tour lui-même.
        agentProvider.enqueueFinal("Résumé compact du travail passé.");
        agentProvider.enqueueFinal("Voilà la suite.");

        service.chat(userId, workspaceId, "on continue");

        // Le workspace porte désormais le résumé et la frontière avancée (affichage inchangé).
        assertThat(workspace.getChatThreadSummary()).isEqualTo("Résumé compact du travail passé.");
        assertThat(workspace.getChatThreadStartedAt()).isEqualTo(boundary);

        // Le DERNIER appel (le tour) rejoue : résumé + 2 messages récents + le nouveau message = 4.
        List<AgentMessage> replayed = agentProvider.lastRequest.messages();
        assertThat(replayed).hasSize(4);
        assertThat(replayed.get(0).content().get(0).toString())
                .contains(AtelierCompactionService.SUMMARY_MARKER);
        assertThat(replayed.get(3).content().get(0))
                .isEqualTo(new fr.claudegateway.agent.AgentContentBlock.Text("on continue"));
    }

    @Test
    void aShortThreadIsNotCompactedAndReplaysEverything() {
        OffsetDateTime t0 = OffsetDateTime.now().minusMinutes(5);
        history.add(msg("USER", "petite demande", t0));
        history.add(msg("ASSISTANT", "petite réponse", t0.plusMinutes(1)));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);
        agentProvider.enqueueFinal("Réponse.");

        service.chat(userId, workspaceId, "et ensuite ?");

        assertThat(workspace.getChatThreadSummary()).isNull();
        // Aucun résumé injecté : user + assistant + nouveau message = 3.
        assertThat(agentProvider.lastRequest.messages()).hasSize(3);
        // Un seul appel fournisseur : pas de compaction.
        assertThat(agentProvider.messageSnapshots).hasSize(1);
    }
}
