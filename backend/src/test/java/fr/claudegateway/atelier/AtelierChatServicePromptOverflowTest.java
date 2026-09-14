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

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * Repli sur débordement de fenêtre (F-117 / SF-117-02) : un 400 « prompt too long » simulé ne tue
 * plus le tour — la boucle compacte puis relance une fois ; si ça dépasse encore, message clair.
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServicePromptOverflowTest {

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

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private Workspace workspace;
    private final List<AtelierMessage> history = new ArrayList<>();
    private final List<AtelierMessage> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
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

    private AtelierChatService service() {
        return new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));
    }

    /** Compaction avec un seuil très haut (jamais proactive) et 2 messages récents gardés. */
    private AtelierCompactionService compaction() {
        return new AtelierCompactionService(messageRepository, workspaceRepository,
                (AiAgentProvider) agentProvider, new AtelierCompactionProperties(true, 1_000_000, 2),
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));
    }

    private AtelierMessage msg(String role, String content, OffsetDateTime at) {
        return AtelierMessage.builder().id(UUID.randomUUID()).workspaceId(workspaceId).userId(userId)
                .role(role).content(content).createdAt(at).build();
    }

    @Test
    void aPromptTooLongIsAbsorbedByCompactionThenRelaunch() {
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(2);
        for (int i = 0; i < 2; i++) {
            history.add(msg("USER", "demande " + i, t0.plusMinutes(i * 2L)));
            history.add(msg("ASSISTANT", "réponse " + i, t0.plusMinutes(i * 2L + 1)));
        }
        OffsetDateTime boundary = history.get(2).getCreatedAt();
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);
        when(messageRepository.findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                workspaceId, userId, boundary)).thenReturn(history.subList(2, 4));

        AtelierChatService service = service();
        service.setCompactionService(compaction());

        // Le 1er appel déborde ; puis la compaction (résumé) et la relance aboutissent.
        agentProvider.enqueuePromptTooLong(1);
        agentProvider.enqueueFinal("Résumé compact.");
        agentProvider.enqueueFinal("Voilà la réponse.");

        AtelierChatService.AtelierChatResult result = service.chat(userId, workspaceId, "continue");

        // Pas d'échec dur : l'utilisateur reçoit sa réponse.
        assertThat(result.reply()).isEqualTo("Voilà la réponse.");
        assertThat(workspace.getChatThreadSummary()).isEqualTo("Résumé compact.");
        assertThat(workspace.getChatThreadStartedAt()).isEqualTo(boundary);
        // Trois appels fournisseur : le débordement, le résumé, la relance.
        assertThat(agentProvider.messageSnapshots).hasSize(3);
    }

    @Test
    void aPersistentOverflowRendersAClearMessageWithoutFailing() {
        OffsetDateTime t0 = OffsetDateTime.now().minusMinutes(10);
        // Deux messages seulement : rien d'ancien à résumer (on garde les 2 derniers) → irréductible.
        history.add(msg("USER", "demande", t0));
        history.add(msg("ASSISTANT", "réponse", t0.plusMinutes(1)));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);

        AtelierChatService service = service();
        service.setCompactionService(compaction());
        agentProvider.enqueuePromptTooLong(1);

        AtelierChatService.AtelierChatResult result = service.chat(userId, workspaceId, "continue");

        assertThat(result.reply()).isEqualTo(AtelierChatService.PROMPT_TOO_LONG_REPLY);
        // Un seul appel : la compaction n'a pas pu réduire, aucune relance.
        assertThat(agentProvider.messageSnapshots).hasSize(1);
    }

    @Test
    void withoutCompactionAnOverflowStillRendersAClearMessage() {
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);

        AtelierChatService service = service(); // compaction NON branchée
        agentProvider.enqueuePromptTooLong(1);

        AtelierChatService.AtelierChatResult result = service.chat(userId, workspaceId, "continue");

        assertThat(result.reply()).isEqualTo(AtelierChatService.PROMPT_TOO_LONG_REPLY);
        assertThat(agentProvider.messageSnapshots).hasSize(1);
    }
}
