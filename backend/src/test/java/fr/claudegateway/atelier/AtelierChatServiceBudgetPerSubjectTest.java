package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * F-195 / SF-195-05 — une interjection prise en compte prolonge le budget du tour (temps et étapes),
 * borné au double ; un arrêt sur budget dit les demandes du tour, pour que celle qui n'a pas abouti ne
 * disparaisse pas.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceBudgetPerSubjectTest {

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
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        when(workspaceService.tree(any(), any())).thenReturn(List.of());
        when(workspaceService.readFile(any(), any(), any())).thenReturn("contenu");
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
    }

    private void build(Integer maxIterations, Duration turnBudget) {
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, maxIterations, null, null, null,
                        null, null, true, null, null, null, turnBudget));
    }

    /** Écoute qui dépose UNE interjection à la {@code at}-ième consultation de la file. */
    private static AtelierProgressListener interjectingAt(int at, String text) {
        AtomicInteger takes = new AtomicInteger();
        return new AtelierProgressListener() {
            @Override
            public void onAction(AtelierStepEvent step) {
            }

            @Override
            public void onText(String text) {
            }

            @Override
            public List<AtelierSteer> takeSteers() {
                return takes.incrementAndGet() == at
                        ? List.of(new AtelierSteer("s1", text, System.currentTimeMillis()))
                        : List.of();
            }
        };
    }

    private void readsThenFinal(int reads) {
        for (int i = 0; i < reads; i++) {
            agentProvider.enqueueToolCall("read_file", "path", "f" + i + ".txt");
        }
        agentProvider.enqueueFinal("Les deux sujets sont traités.");
    }

    @Test
    void anInterjectionAddsStepsToTheTurn() {
        build(2, null);
        service.setInterjectionExtension(0L, 3);
        readsThenFinal(3);

        AtelierChatResult result = service.chatStreaming(userId, workspaceId, "sujet A",
                interjectingAt(2, "et aussi le sujet B"));

        assertThat(result.reply()).contains("Les deux sujets sont traités.");
    }

    @Test
    void withoutExtensionTheStepLimitStopsAndNamesEveryRequest() {
        build(2, null);
        service.setInterjectionExtension(0L, 0);
        readsThenFinal(3);

        AtelierChatResult result = service.chatStreaming(userId, workspaceId, "sujet A",
                interjectingAt(2, "et aussi le sujet B"));

        assertThat(result.reply()).doesNotContain("Les deux sujets sont traités.")
                .contains("Ce message portait 2 demandes")
                .contains("1) sujet A")
                .contains("2) et aussi le sujet B")
                .doesNotContain("[Interjection de l'utilisateur");
    }

    @Test
    void theExtensionNeverExceedsTwiceTheStepLimit() {
        build(2, null);
        service.setInterjectionExtension(0L, 100);
        readsThenFinal(6);

        AtelierChatResult result = service.chatStreaming(userId, workspaceId, "sujet A",
                interjectingAt(2, "et aussi le sujet B"));

        // 2 × 2 = 4 étapes au plus : la 5e lecture n'est jamais demandée, la réponse finale non plus.
        assertThat(result.reply()).doesNotContain("Les deux sujets sont traités.")
                .contains("Ce message portait 2 demandes");
    }

    @Test
    void anInterjectionAddsTimeToTheTurn() {
        build(null, Duration.ofMillis(500));
        service.setInterjectionExtension(500L, 0);
        agentProvider.onTurn(() -> {
            try {
                Thread.sleep(650L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        readsThenFinal(2);

        // Le 1er appel dure 650 ms : sans prolongation, le contrôle suivant tomberait au-delà de 500.
        AtelierChatResult result = service.chatStreaming(userId, workspaceId, "sujet A",
                interjectingAt(2, "et aussi le sujet B"));

        assertThat(result.reply()).contains("Les deux sujets sont traités.");
    }

    @Test
    void withoutInterjectionTheTimeBudgetIsUnchanged() {
        build(null, Duration.ofMillis(500));
        service.setInterjectionExtension(500L, 0);
        agentProvider.onTurn(() -> {
            try {
                Thread.sleep(650L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        readsThenFinal(2);

        AtelierChatResult result = service.chatStreaming(userId, workspaceId, "sujet A", interjectingAt(99, ""));

        assertThat(result.reply()).isEqualTo(AtelierChatService.BUDGET_REACHED_REPLY);
    }

    @Test
    void theNoteStripsTheLabelAndBoundsEachRequest() {
        String note = AtelierChatService.unfinishedRequestsNote(List.of("premier sujet",
                AtelierChatService.interjection(0L, "x".repeat(300))));

        assertThat(note).contains("1) premier sujet").contains("2) xxx").contains("…")
                .doesNotContain("Interjection de l'utilisateur");
        assertThat(note.lines().filter(line -> line.startsWith("2) ")).findFirst().orElseThrow())
                .hasSizeLessThanOrEqualTo(3 + 120);
    }
}
