package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.questions.QuestionToolCatalog;
import fr.claudegateway.atelier.questions.TurnQuestions;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * F-210 / SF-210-01 — découper et suivre les questions dans la boucle : outils offerts et stables,
 * instantané relayé, actions rattachées, relevé rangé ; rien ne change sans découpage.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceQuestionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    private final List<TurnQuestions.Snapshot> snapshots = new ArrayList<>();
    private final List<AtelierProgressListener.AtelierStepEvent> steps = new ArrayList<>();

    private final AtelierProgressListener listener = new AtelierProgressListener() {
        @Override
        public void onQuestions(TurnQuestions.Snapshot snapshot) {
            snapshots.add(snapshot);
        }

        @Override
        public void onAction(AtelierStepEvent step) {
            steps.add(step);
        }

        @Override
        public void onText(String text) {
        }
    };

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));
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
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
    }

    private static AgentToolCall call(String id, String tool, String json) {
        try {
            return new AgentToolCall(id, tool, MAPPER.readTree(json));
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private JsonNode savedAssistantReport() throws Exception {
        org.mockito.ArgumentCaptor<AtelierMessage> saved = org.mockito.ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        AtelierMessage assistant = saved.getAllValues().stream().filter(m -> "ASSISTANT".equals(m.getRole()))
                .reduce((a, b) -> b).orElseThrow();
        return MAPPER.readTree(assistant.getTerminalJson());
    }

    @Test
    void bothToolsAreOfferedWithStableDefinitions() {
        agentProvider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "premier");
        List<AgentTool> first = agentProvider.lastRequest.tools();
        String system = agentProvider.lastRequest.system();
        agentProvider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "second");

        assertThat(first).extracting(AgentTool::name).contains("decouper_demande", "suivre_question");
        assertThat(agentProvider.lastRequest.tools()).isEqualTo(first);
        assertThat(agentProvider.lastRequest.system()).isEqualTo(system);
    }

    @Test
    void aSplitTurnRelaysItsQuestionsAttachesActionsAndKeepsTheReport() throws Exception {
        agentProvider.enqueueTurn(new AgentTurn("", List.of(
                call("c1", QuestionToolCatalog.SPLIT, "{\"questions\":[{\"id\":1,\"libelle\":\"Lire A\"},"
                        + "{\"id\":2,\"libelle\":\"Lire B\"}]}"),
                call("c2", "read_file", "{\"path\":\"a.txt\"}")), false, 5, 5));
        agentProvider.enqueueTurn(new AgentTurn("", List.of(
                call("c3", QuestionToolCatalog.TRACK, "{\"id\":2,\"etat\":\"EN_COURS\"}"),
                call("c4", "read_file", "{\"path\":\"b.txt\"}")), false, 5, 5));
        agentProvider.enqueueFinal("A et B lus.");

        service.chatStreaming(userId, workspaceId, "lis A, et aussi B", listener);

        assertThat(snapshots).isNotEmpty();
        assertThat(snapshots.get(0).questions()).extracting(TurnQuestions.QuestionView::state)
                .containsExactly("EN_COURS", "EN_ATTENTE");
        assertThat(steps).extracting(AtelierProgressListener.AtelierStepEvent::question).containsExactly(1, 2);
        // Le dernier instantané est clos : tour abouti.
        assertThat(snapshots.get(snapshots.size() - 1).questions())
                .extracting(TurnQuestions.QuestionView::state).containsExactly("TERMINEE", "TERMINEE");

        JsonNode report = savedAssistantReport();
        JsonNode questions = report.path("questions").path("questions");
        assertThat(questions).hasSize(2);
        assertThat(questions.get(0).path("toolUseIds").get(0).asText()).isEqualTo("c2");
        assertThat(questions.get(1).path("toolUseIds").get(0).asText()).isEqualTo("c4");
        // Aucun bloc de transcription pour les outils de questions.
        assertThat(report.path("blocks").toString()).doesNotContain(QuestionToolCatalog.SPLIT)
                .doesNotContain(QuestionToolCatalog.TRACK).contains("read_file");
    }

    @Test
    void aTurnWithoutSplitIsUnchanged() throws Exception {
        agentProvider.enqueueTurn(new AgentTurn("", List.of(call("c1", "read_file", "{\"path\":\"a.txt\"}")),
                false, 5, 5));
        agentProvider.enqueueFinal("lu.");

        service.chatStreaming(userId, workspaceId, "lis A", listener);

        assertThat(snapshots).isEmpty();
        assertThat(steps).extracting(AtelierProgressListener.AtelierStepEvent::question).containsOnlyNulls();
        assertThat(savedAssistantReport().has("questions")).isFalse();
    }

    @Test
    void anInvalidSplitIsAToolErrorAndTheTurnGoesOn() throws Exception {
        agentProvider.enqueueTurn(new AgentTurn("", List.of(
                call("c1", QuestionToolCatalog.SPLIT, "{\"questions\":[{\"id\":1,\"libelle\":\"Seule\"}]}"),
                call("c2", "read_file", "{\"path\":\"a.txt\"}")), false, 5, 5));
        agentProvider.enqueueFinal("lu.");

        AtelierChatService.AtelierChatResult result =
                service.chatStreaming(userId, workspaceId, "lis A", listener);

        assertThat(result.reply()).isEqualTo("lu.");
        assertThat(snapshots).isEmpty();
        assertThat(agentProvider.messageSnapshots.get(agentProvider.messageSnapshots.size() - 1))
                .contains("deux demandes distinctes");
    }

    @Test
    void aSplitAnswerIsReadPerQuestionAndThePersistedTextHasNoMarker() throws Exception {
        agentProvider.enqueueTurn(new AgentTurn("", List.of(
                call("c1", QuestionToolCatalog.SPLIT, "{\"questions\":[{\"id\":1,\"libelle\":\"Lire A\"},"
                        + "{\"id\":2,\"libelle\":\"Lire B\"}]}"),
                call("c2", "read_file", "{\"path\":\"a.txt\"}")), false, 5, 5));
        agentProvider.enqueueFinal("<<question id=\"1\">>\n<<essentiel>>\nA dit oui.\n<</essentiel>>\nDétail A.\n"
                + "<<suites>>\n[{\"titre\":\"Relire A\",\"pourquoi\":\"lu en partie\",\"etiquette\":\"Vérifier\","
                + "\"demande\":\"Relis A en entier\"}]\n<</suites>>\n<</question>>\n"
                + "<<question id=\"2\">>\n<<essentiel>>\nB dit non.\n<</essentiel>>\n<</question>>");

        AtelierChatService.AtelierChatResult result =
                service.chatStreaming(userId, workspaceId, "lis A, et aussi B", listener);

        assertThat(result.reply()).doesNotContain("<<question").doesNotContain("<</question>>")
                .contains("A dit oui.").contains("B dit non.");
        TurnQuestions.Snapshot last = snapshots.get(snapshots.size() - 1);
        assertThat(last.answers()).hasSize(2);
        assertThat(last.answers().get(0).nextSteps()).extracting(n -> n.title()).containsExactly("Relire A");
        JsonNode report = savedAssistantReport();
        assertThat(report.path("answers")).hasSize(2);
        assertThat(report.path("answers").get(1).path("essential").asText()).isEqualTo("B dit non.");
        assertThat(report.path("questions").has("answers")).isFalse();
    }

    @Test
    void theDoctrineFollowsTheSuitesAndKeepsTheOthersIntact() {
        agentProvider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "bonjour");
        String system = agentProvider.lastRequest.system();

        assertThat(system).contains(AtelierChatService.QUESTION_ANSWER_DOCTRINE)
                .contains(AtelierChatService.MULTI_SUBJECT_DOCTRINE)
                .contains(AtelierChatService.NEXT_STEPS_DOCTRINE);
        assertThat(system.indexOf(AtelierChatService.QUESTION_ANSWER_DOCTRINE))
                .isGreaterThan(system.indexOf(AtelierChatService.NEXT_STEPS_DOCTRINE));
        assertThat(AtelierChatService.QUESTION_ANSWER_DOCTRINE).contains("AU MOINS DEUX demandes distinctes");
    }
}
