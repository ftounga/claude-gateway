package fr.claudegateway.atelier.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;

/**
 * Vérifie l'orchestration de {@link AtelierSessionService} (F-28 / Phase 2, ADR-013) avec un
 * {@link ManagedAgentProvider} mocké (aucune session live) : ordre du pont fichiers, isolation
 * {@code user_id}, refus flag off, et terminaison systématique de la session ({@code finally}).
 */
@ExtendWith(MockitoExtension.class)
class AtelierSessionServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();

    @Mock
    private ManagedAgentProvider provider;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private AtelierAgentBootstrapService bootstrapService;

    private AtelierAgentProperties enabled() {
        return new AtelierAgentProperties(true, null, null, null, null, null, null, null, null);
    }

    private AtelierAgentProperties disabled() {
        return new AtelierAgentProperties(false, null, null, null, null, null, null, null, null);
    }

    private AtelierAgentConfig config() {
        return AtelierAgentConfig.builder()
                .agentId("agent_1").environmentId("env_1").agentVersion("v1").build();
    }

    private AtelierSessionService service(AtelierAgentProperties props) {
        return new AtelierSessionService(provider, workspaceService, bootstrapService, props);
    }

    @Test
    void runTaskMountsFilesRunsSessionAndSyncsOutputsInOrder() {
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("src/a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "src/a.txt")).thenReturn("class A {}");
        when(provider.uploadFile(eq("src_a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_1")).thenReturn(List.of(
                new OutputFile("out_1", "/workspace/src/a.txt"),
                new OutputFile("out_2", "/mnt/session/outputs/new.txt"),
                new OutputFile("out_3", "plain.txt")));
        when(provider.downloadFile("out_1")).thenReturn("A modifié".getBytes());
        when(provider.downloadFile("out_2")).thenReturn("nouveau".getBytes());
        when(provider.downloadFile("out_3")).thenReturn("simple".getBytes());

        AtelierSessionResult result = service(enabled()).runTask(USER, WORKSPACE, "Corrige le bug.");

        assertThat(result.reply()).isEqualTo("Terminé.");
        assertThat(result.changedFiles()).containsExactly("src/a.txt", "new.txt", "plain.txt");

        // Chemins de montage : uniquement sous /workspace/, à partir de l'arbo du user.
        ArgumentCaptor<List<FileMount>> mountsCaptor = ArgumentCaptor.forClass(List.class);
        verify(provider).createSession(eq("agent_1"), eq("env_1"), mountsCaptor.capture());
        assertThat(mountsCaptor.getValue()).containsExactly(new FileMount("file_in", "/workspace/src/a.txt"));

        // Sorties réécrites via WorkspaceService (isolation + garde-fous Phase 1).
        verify(workspaceService).writeFile(USER, WORKSPACE, "src/a.txt", "A modifié");
        verify(workspaceService).writeFile(USER, WORKSPACE, "new.txt", "nouveau");
        verify(workspaceService).writeFile(USER, WORKSPACE, "plain.txt", "simple");

        // Ordre : isolation → upload → create → send → await → outputs → download → write → terminate.
        InOrder order = inOrder(workspaceService, provider);
        order.verify(workspaceService).requireOwned(USER, WORKSPACE);
        order.verify(workspaceService).tree(USER, WORKSPACE);
        order.verify(provider).uploadFile(eq("src_a.txt"), any());
        order.verify(provider).createSession(eq("agent_1"), eq("env_1"), anyList());
        order.verify(provider).sendUserMessage("sess_1", "Corrige le bug.");
        order.verify(provider).awaitCompletion(eq("sess_1"), any(), anyInt(), any());
        order.verify(provider).listOutputs("sess_1");
        order.verify(provider).downloadFile("out_1");
        order.verify(provider).terminateSession("sess_1");
    }

    @Test
    void runTaskStreamingNotifiesListenerThenBridgesFilesAndResultLikeRunTask() {
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("src/a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "src/a.txt")).thenReturn("class A {}");
        when(provider.uploadFile(eq("src_a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList()))
                .thenReturn(new ManagedSession("sess_1"));
        // Le provider relaie des events au listener passé (bridge) puis renvoie la réponse agrégée.
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any())).thenAnswer(inv -> {
            ManagedEventListener sink = inv.getArgument(3);
            sink.onStatus("running");
            sink.onAgentText("Je corrige.");
            sink.onAction("bash", "ls -la");
            sink.onStatus("idle");
            return new SessionRun("Terminé.", "end_turn");
        });
        when(provider.listOutputs("sess_1")).thenReturn(List.of(new OutputFile("out_1", "/workspace/src/a.txt")));
        when(provider.downloadFile("out_1")).thenReturn("A modifié".getBytes());

        RecordingAgentListener listener = new RecordingAgentListener();
        AtelierSessionResult result = service(enabled())
                .runTaskStreaming(USER, WORKSPACE, "Corrige le bug.", listener);

        // Résultat = pont fichiers + réponse, identique à runTask.
        assertThat(result.reply()).isEqualTo("Terminé.");
        assertThat(result.changedFiles()).containsExactly("src/a.txt");
        verify(workspaceService).writeFile(USER, WORKSPACE, "src/a.txt", "A modifié");
        // Les étapes relayées ont bien été transmises au listener applicatif.
        assertThat(listener.texts).containsExactly("Je corrige.");
        assertThat(listener.actions).containsExactly("bash:ls -la");
        assertThat(listener.states).containsExactly("running", "idle");
    }

    @Test
    void runTaskStreamingChecksOwnershipFirstAndNeverCallsProviderWhenNotOwned() {
        when(workspaceService.requireOwned(USER, WORKSPACE))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable."));

        assertThatThrownBy(() -> service(enabled())
                .runTaskStreaming(USER, WORKSPACE, "x", AtelierAgentListener.NOOP))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verifyNoInteractions(provider);
        verifyNoInteractions(bootstrapService);
    }

    @Test
    void runTaskStreamingRefusesWhenFlagOffWithoutAnyProviderCall() {
        assertThatThrownBy(() -> service(disabled())
                .runTaskStreaming(USER, WORKSPACE, "x", AtelierAgentListener.NOOP))
                .isInstanceOf(AtelierAgentDisabledException.class);

        verify(workspaceService).requireOwned(USER, WORKSPACE);
        verifyNoInteractions(provider);
        verifyNoInteractions(bootstrapService);
    }

    @Test
    void runTaskStreamingTerminatesSessionWhenProviderFailsDuringRun() {
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "a.txt")).thenReturn("x");
        when(provider.uploadFile(eq("a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
                .thenThrow(new AgentProviderException("boom"));

        assertThatThrownBy(() -> service(enabled())
                .runTaskStreaming(USER, WORKSPACE, "go", AtelierAgentListener.NOOP))
                .isInstanceOf(AgentProviderException.class);

        verify(provider).terminateSession("sess_1");
    }

    /** Écouteur applicatif de test enregistrant les notifications reçues. */
    private static final class RecordingAgentListener implements AtelierAgentListener {
        private final List<String> texts = new java.util.ArrayList<>();
        private final List<String> actions = new java.util.ArrayList<>();
        private final List<String> states = new java.util.ArrayList<>();

        @Override
        public void onAgentText(String text) {
            texts.add(text);
        }

        @Override
        public void onAction(String tool, String detail) {
            actions.add(tool + ":" + detail);
        }

        @Override
        public void onStatus(String state) {
            states.add(state);
        }
    }

    @Test
    void runTaskChecksOwnershipFirstAndNeverCallsProviderWhenNotOwned() {
        when(workspaceService.requireOwned(USER, WORKSPACE))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable."));

        assertThatThrownBy(() -> service(enabled()).runTask(USER, WORKSPACE, "x"))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verifyNoInteractions(provider);
        verifyNoInteractions(bootstrapService);
    }

    @Test
    void runTaskRefusesWhenFlagOffWithoutAnyProviderCall() {
        assertThatThrownBy(() -> service(disabled()).runTask(USER, WORKSPACE, "x"))
                .isInstanceOf(AtelierAgentDisabledException.class);

        // Isolation vérifiée d'abord, mais aucun appel réseau (provider/bootstrap intouchés).
        verify(workspaceService).requireOwned(USER, WORKSPACE);
        verifyNoInteractions(provider);
        verifyNoInteractions(bootstrapService);
    }

    @Test
    void runTaskTerminatesSessionWhenProviderFailsDuringRun() {
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "a.txt")).thenReturn("x");
        when(provider.uploadFile(eq("a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
                .thenThrow(new AgentProviderException("boom"));

        assertThatThrownBy(() -> service(enabled()).runTask(USER, WORKSPACE, "go"))
                .isInstanceOf(AgentProviderException.class);

        verify(provider).terminateSession("sess_1");
    }

    @Test
    void runTaskPropagatesTimeoutAndStillTerminatesSession() {
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "a.txt")).thenReturn("x");
        when(provider.uploadFile(eq("a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
                .thenThrow(new AgentSessionTimeoutException("timeout"));

        assertThatThrownBy(() -> service(enabled()).runTask(USER, WORKSPACE, "go"))
                .isInstanceOf(AgentSessionTimeoutException.class);

        verify(provider).terminateSession("sess_1");
    }

    @Test
    void uploadFilenameFlattensForbiddenCharacters() {
        // La Files API d'Anthropic rejette « / » (et autres) dans le nom : on aplatit à l'upload.
        assertThat(AtelierSessionService.uploadFilename("src/facture.js")).isEqualTo("src_facture.js");
        assertThat(AtelierSessionService.uploadFilename("a/b/c.txt")).isEqualTo("a_b_c.txt");
        assertThat(AtelierSessionService.uploadFilename("plain.txt")).isEqualTo("plain.txt");
        assertThat(AtelierSessionService.uploadFilename("na me!.md")).isEqualTo("na_me_.md");
    }
}
