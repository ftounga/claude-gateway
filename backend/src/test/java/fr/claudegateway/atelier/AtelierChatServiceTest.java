package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierStepEvent;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaExceededException;
import fr.claudegateway.quota.QuotaService;

/**
 * Tests unitaires de la boucle tool-use et du relais de progression (F-28 / SF-28-05). Le fournisseur
 * d'agent est un stub scriptable ; les collaborateurs (workspace, quota, repo) sont mockés. Vérifie la
 * non-régression du mode synchrone, le relais des étapes en streaming, et l'ordre pré-vol
 * (quota/isolation) avant tout appel fournisseur.
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private ModelCatalog modelCatalog;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    /** Listener de capture : enregistre l'ordre des notifications d'étapes et de texte. */
    private static final class RecordingListener implements AtelierProgressListener {
        final List<AtelierStepEvent> actions = new ArrayList<>();
        final List<String> texts = new ArrayList<>();

        @Override
        public void onAction(AtelierStepEvent step) {
            actions.add(step);
        }

        @Override
        public void onText(String text) {
            texts.add(text);
        }
    }

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService, modelCatalog);
    }

    private void stubHappyPath() {
        when(modelCatalog.defaultModel()).thenReturn("claude-model");
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        // Le repo renvoie un message porteur d'un id (utilisé pour le messageId assistant).
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        when(workspaceService.readFile(any(), any(), any())).thenReturn("contenu du fichier");
    }

    @Test
    void chatStreamingNotifiesListenerWithReadActionBeforeDoneResult() {
        stubHappyPath();
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueFinal("J'ai lu notes.txt.");
        RecordingListener listener = new RecordingListener();

        AtelierChatResult result = service.chatStreaming(userId, workspaceId, "lis notes.txt", listener);

        // CA1 : une notification d'action par appel d'outil, avec le bon type/chemin.
        assertThat(listener.actions).hasSize(1);
        assertThat(listener.actions.get(0)).isEqualTo(new AtelierStepEvent("read", "notes.txt"));
        // Le résultat final porte la réponse et l'action récapitulée.
        assertThat(result.reply()).isEqualTo("J'ai lu notes.txt.");
        assertThat(result.actions()).extracting(a -> a.type()).containsExactly("read");
        assertThat(result.messageId()).isNotNull();
    }

    @Test
    void chatStreamingReturnsSameResultAsSynchronousChat() {
        stubHappyPath();
        // Deux appels indépendants avec le même script → mêmes reply/actions.
        agentProvider.enqueueToolCall("read_file", "path", "a.txt");
        agentProvider.enqueueFinal("Fait.");
        AtelierChatResult sync = service.chat(userId, workspaceId, "lis a.txt");

        agentProvider.reset();
        stubHappyPath();
        agentProvider.enqueueToolCall("read_file", "path", "a.txt");
        agentProvider.enqueueFinal("Fait.");
        RecordingListener listener = new RecordingListener();
        AtelierChatResult streamed = service.chatStreaming(userId, workspaceId, "lis a.txt", listener);

        assertThat(streamed.reply()).isEqualTo(sync.reply());
        assertThat(streamed.actions()).usingRecursiveComparison().isEqualTo(sync.actions());
    }

    @Test
    void searchAndListAndWriteActionsAreRelayedWithCorrectTypeAndPath() {
        stubHappyPath();
        when(workspaceService.tree(any(), any())).thenReturn(List.of());
        agentProvider.enqueueToolCall("list_files");
        agentProvider.enqueueToolCall("search_files", "query", "TODO");
        agentProvider.enqueueToolCall("write_file", "path", "b.txt", "content", "x");
        agentProvider.enqueueFinal("Terminé.");
        RecordingListener listener = new RecordingListener();

        service.chatStreaming(userId, workspaceId, "fais des trucs", listener);

        assertThat(listener.actions).containsExactly(
                new AtelierStepEvent("list", null),
                new AtelierStepEvent("search", "TODO"),
                new AtelierStepEvent("write", "b.txt"));
    }

    @Test
    void quotaExceededIsRaisedBeforeAnyProviderCall() {
        // CA3 : le quota est vérifié avant tout appel fournisseur (aucun tour joué).
        org.mockito.Mockito.doThrow(new QuotaExceededException("quota atteint"))
                .when(quotaService).assertWithinQuota(userId);
        RecordingListener listener = new RecordingListener();

        assertThatThrownBy(() -> service.chatStreaming(userId, workspaceId, "salut", listener))
                .isInstanceOf(QuotaExceededException.class);

        assertThat(agentProvider.lastRequest).isNull();
        assertThat(listener.actions).isEmpty();
        verify(quotaService, never()).recordUsage(any(), anyInt(), anyInt());
    }

    @Test
    void otherUsersWorkspaceRaisesBeforeAnyProviderCall() {
        // CA4 : isolation — un workspace non possédé lève avant tout accès fichier/fournisseur.
        org.mockito.Mockito.doThrow(new WorkspaceNotFoundException("introuvable"))
                .when(workspaceService).requireOwned(eq(userId), eq(workspaceId));
        RecordingListener listener = new RecordingListener();

        assertThatThrownBy(() -> service.chatStreaming(userId, workspaceId, "salut", listener))
                .isInstanceOf(WorkspaceNotFoundException.class);

        assertThat(agentProvider.lastRequest).isNull();
        verify(quotaService, never()).recordUsage(any(), anyInt(), anyInt());
    }
}
