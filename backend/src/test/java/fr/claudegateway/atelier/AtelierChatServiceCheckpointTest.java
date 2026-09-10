package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpoint;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Le crochet d'écriture <b>branché dans la boucle</b> (F-50 / SF-50-01).
 *
 * <p>Ce que ces tests protègent : qu'un contrôle bloquant produise le geste attendu du modèle — un
 * {@code tool_result} en <b>erreur</b> portant l'action corrective, exactement comme un refus de la
 * porte de confirmation (SF-38-08) — et qu'en l'absence de contrôle, <b>rien ne change</b>.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceCheckpointTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private fr.claudegateway.runner.exec.RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;

    private StubAiAgentProvider agentProvider;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    /** Contrôle d'écriture qui note ce qu'on lui a soumis, et rend le verdict qu'on lui a donné. */
    private static final class RecordingCheckpoint implements AtelierCheckpoint {
        final List<AtelierCheckpointContext> seen = new ArrayList<>();
        private final AtelierCheckpointVerdict verdict;

        RecordingCheckpoint(AtelierCheckpointVerdict verdict) {
            this.verdict = verdict;
        }

        @Override
        public AtelierCheckpointKind kind() {
            return AtelierCheckpointKind.AFTER_FILE_WRITE;
        }

        @Override
        public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
            seen.add(context);
            return verdict;
        }
    }

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        when(workspaceService.readFile(any(), any(), any())).thenReturn("ancien contenu");
    }

    private AtelierChatService serviceWith(AtelierCheckpoint... checkpoints) {
        return new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true),
                new AtelierCheckpointRunner(List.of(checkpoints)));
    }

    /** Dernier {@code tool_result} transmis au modèle : ce que l'outil lui a réellement rendu. */
    private AgentContentBlock.ToolResult lastToolResult() {
        AgentContentBlock.ToolResult found = null;
        for (AgentMessage message : agentProvider.lastRequest.messages()) {
            for (AgentContentBlock block : message.content()) {
                if (block instanceof AgentContentBlock.ToolResult result) {
                    found = result;
                }
            }
        }
        assertThat(found).as("aucun tool_result transmis au modèle").isNotNull();
        return found;
    }

    @Test
    void aBlockingCheckpointTurnsASuccessfulWriteIntoAnErrorCarryingTheCorrectiveAction() {
        RecordingCheckpoint checkpoint = new RecordingCheckpoint(
                AtelierCheckpointVerdict.block("Retire la clé en clair de notes.txt, puis reprends."));
        AtelierChatService service = serviceWith(checkpoint);
        agentProvider.enqueueToolCall("write_file", "path", "notes.txt", "content", "bonjour");
        agentProvider.enqueueFinal("Corrigé.");

        AtelierChatResult result = service.chat(userId, workspaceId, "écris notes.txt");

        AgentContentBlock.ToolResult toolResult = lastToolResult();
        assertThat(toolResult.isError()).isTrue();
        assertThat(toolResult.content()).isEqualTo(
                "Écriture contrôlée : Retire la clé en clair de notes.txt, puis reprends.");
        // Le fichier a bel et bien été écrit : l'éditeur ouvert doit se rafraîchir malgré le blocage.
        assertThat(result.actions()).extracting("type", "path").containsExactly(
                org.assertj.core.groups.Tuple.tuple("write", "notes.txt"));
    }

    @Test
    void theCheckpointReceivesTheOwnerTheProjectThePathAndTheRequestedContent() {
        RecordingCheckpoint checkpoint = new RecordingCheckpoint(AtelierCheckpointVerdict.proceed());
        AtelierChatService service = serviceWith(checkpoint);
        agentProvider.enqueueToolCall("write_file", "path", "notes.txt", "content", "bonjour atelier");
        agentProvider.enqueueFinal("Écrit.");

        service.chat(userId, workspaceId, "écris notes.txt");

        assertThat(checkpoint.seen).hasSize(1);
        AtelierCheckpointContext context = checkpoint.seen.get(0);
        // Isolation : le couple remis au contrôle est celui du tour, déjà vérifié possédé.
        assertThat(context.userId()).isEqualTo(userId);
        assertThat(context.workspaceId()).isEqualTo(workspaceId);
        assertThat(context.kind()).isEqualTo(AtelierCheckpointKind.AFTER_FILE_WRITE);
        assertThat(context.toolName()).isEqualTo("write_file");
        assertThat(context.path()).isEqualTo("notes.txt");
        assertThat(context.content()).isEqualTo("bonjour atelier");
    }

    @Test
    void aTargetedEditIsControlledToo_withItsReplacementAsContent() {
        RecordingCheckpoint checkpoint = new RecordingCheckpoint(
                AtelierCheckpointVerdict.block("Reprends l'édition de notes.txt."));
        AtelierChatService service = serviceWith(checkpoint);
        agentProvider.enqueueToolCall("edit_file", "path", "notes.txt",
                "old_string", "ancien", "new_string", "nouveau");
        agentProvider.enqueueFinal("Corrigé.");

        service.chat(userId, workspaceId, "édite notes.txt");

        assertThat(checkpoint.seen).hasSize(1);
        assertThat(checkpoint.seen.get(0).toolName()).isEqualTo("edit_file");
        assertThat(checkpoint.seen.get(0).content()).isEqualTo("nouveau");
        assertThat(lastToolResult().isError()).isTrue();
        assertThat(lastToolResult().content()).startsWith("Écriture contrôlée : ");
    }

    @Test
    void aPassingCheckpointLeavesTheToolResultUntouched() {
        AtelierChatService service = serviceWith(new RecordingCheckpoint(AtelierCheckpointVerdict.proceed()));
        agentProvider.enqueueToolCall("write_file", "path", "notes.txt", "content", "bonjour");
        agentProvider.enqueueFinal("Écrit.");

        service.chat(userId, workspaceId, "écris notes.txt");

        assertThat(lastToolResult().isError()).isFalse();
        assertThat(lastToolResult().content()).isEqualTo("Fichier écrit : notes.txt");
    }

    @Test
    void withoutAnyCheckpointTheLoopBehavesExactlyAsBefore() {
        AtelierChatService service = serviceWith();
        agentProvider.enqueueToolCall("write_file", "path", "notes.txt", "content", "bonjour");
        agentProvider.enqueueFinal("Écrit.");

        AtelierChatResult result = service.chat(userId, workspaceId, "écris notes.txt");

        assertThat(lastToolResult().isError()).isFalse();
        assertThat(result.reply()).isEqualTo("Écrit.");
    }

    @Test
    void aFailedWriteIsNeverControlled() {
        // Rien n'a été produit qu'on puisse juger, et empiler un second message d'erreur sur le
        // premier brouillerait la correction attendue.
        RecordingCheckpoint checkpoint = new RecordingCheckpoint(
                AtelierCheckpointVerdict.block("Ne devrait jamais être rendu."));
        AtelierChatService service = serviceWith(checkpoint);
        org.mockito.Mockito.doThrow(new InvalidFilePathException("Chemin de fichier invalide."))
                .when(workspaceService).writeFile(any(), any(), any(), any());
        agentProvider.enqueueToolCall("write_file", "path", "../evade.txt", "content", "bonjour");
        agentProvider.enqueueFinal("Compris.");

        service.chat(userId, workspaceId, "écris hors du projet");

        assertThat(checkpoint.seen).isEmpty();
        assertThat(lastToolResult().isError()).isTrue();
        assertThat(lastToolResult().content()).isEqualTo("Chemin de fichier invalide.");
    }

    @Test
    void aReadIsNeverControlled() {
        RecordingCheckpoint checkpoint = new RecordingCheckpoint(
                AtelierCheckpointVerdict.block("Ne devrait jamais être rendu."));
        AtelierChatService service = serviceWith(checkpoint);
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueFinal("Lu.");

        service.chat(userId, workspaceId, "lis notes.txt");

        assertThat(checkpoint.seen).isEmpty();
        assertThat(lastToolResult().isError()).isFalse();
    }
}
