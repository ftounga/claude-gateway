package fr.claudegateway.atelier.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.agent.ManagedAgentProvider.SessionUsage;
import fr.claudegateway.quota.QuotaExceededException;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.SandboxLimitExceededException;
import fr.claudegateway.quota.UsageSnapshot;

/**
 * Vérifie l'orchestration de {@link AtelierSessionService} (F-28 / Phase 2, ADR-013) avec un
 * {@link ManagedAgentProvider} mocké (aucune session live) : ordre du pont fichiers, isolation
 * {@code user_id}, refus flag off, et — depuis F-30 SF-30-04 (ADR-014) — <b>session persistante</b>
 * par workspace : réutilisation sans remontage, reprise unique sur session injouable, resync
 * incrémental et décompte en <b>delta</b>.
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
    @Mock
    private QuotaService quotaService;
    @Mock
    private WorkspaceRepository workspaceRepository;
    @Mock
    private fr.claudegateway.atelier.AtelierMessageRepository messageRepository;
    @Mock
    private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock
    private fr.claudegateway.atelier.ProjectInstructionsService instructionsService;
    @Mock
    private McpVaultService mcpVaultService;
    /** Diffusion inter-pods (F-38 / SF-38-13) : muette par défaut, vérifiée là où elle compte. */
    @Mock
    private fr.claudegateway.runner.relay.RunnerRelayBroadcaster relayBroadcaster;

    /**
     * Quota restant large par défaut : le plafond de dépense de la session (F-36 / SF-36-01) est alors
     * borné par le plafond par run, comme dans le cas courant. Les tests qui visent la conversion du
     * quota restant réoutillent ce stub.
     */
    @BeforeEach
    void largeRemainingQuota() {
        givenRemainingTokens(12_000_000L);
    }

    private AtelierAgentProperties enabled() {
        return new AtelierAgentProperties(true, null, null, null, null, null, null, null, null, null,
                null, null, null, false, null, null, Duration.ZERO);
    }

    private AtelierAgentProperties disabled() {
        return new AtelierAgentProperties(false, null, null, null, null, null, null, null, null, null,
                null, null, null, false, null, null, Duration.ZERO);
    }

    /** Atelier actif avec relevé de progression (F-30 SF-30-13) : intervalle court, mais non nul. */
    private AtelierAgentProperties withProgress(Duration interval) {
        return new AtelierAgentProperties(true, null, null, null, null, null, null, null, null, null,
                null, null, null, false, null, null, interval);
    }

    /** Atelier actif avec la délégation ouverte (F-35 SF-35-01), plafond de roster explicite. */
    private AtelierAgentProperties withSubagents(int maxSubagents) {
        return new AtelierAgentProperties(true, null, null, null, null, null, null, null, null, null,
                null, null, null, true, maxSubagents, null, Duration.ZERO);
    }

    private AtelierAgentConfig config() {
        return AtelierAgentConfig.builder()
                .agentId("agent_1").environmentId("env_1").agentVersion("v1").build();
    }

    private AtelierSessionService service(AtelierAgentProperties props) {
        return service(props, costProperties());
    }

    private AtelierSessionService service(AtelierAgentProperties props, AtelierCostProperties cost) {
        return service(props, cost, new AtelierDiffProperties(null, null));
    }

    /** Service avec des bornes de diff explicites (F-37 / SF-37-01). */
    private AtelierSessionService service(AtelierAgentProperties props, AtelierCostProperties cost,
            AtelierDiffProperties diff) {
        return new AtelierSessionService(provider, workspaceService, bootstrapService, props, quotaService,
                workspaceRepository, messageRepository, gitTokenService, instructionsService,
                mcpVaultService, cost, diff, relayBroadcaster);
    }

    /** Réglages de dépense par défaut (F-36 / SF-36-01) : plafond 2 $, plancher 0,10 $, 9 $/M. */
    private AtelierCostProperties costProperties() {
        return costProperties(null);
    }

    /** Réglages de dépense avec un markup explicite (F-36 / SF-36-02). */
    private AtelierCostProperties costProperties(java.math.BigDecimal markup) {
        return new AtelierCostProperties(null, null, null, null, markup);
    }

    /** Quota restant large : le plafond par run est alors le facteur limitant (F-36 / SF-36-01). */
    private void givenRemainingTokens(long remaining) {
        lenient().when(quotaService.currentUsage(USER)).thenReturn(new UsageSnapshot(
                0L, remaining, remaining, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1)));
    }

    /** Workspace possédé, portant (ou non) une session sandbox déjà ouverte (F-30 SF-30-04). */
    private Workspace ws(String sessionId) {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE);
        workspace.setUserId(USER);
        workspace.setName("projet");
        workspace.setAgentSessionId(sessionId);
        return workspace;
    }

    @Test
    void runTaskRefusesAWorkspaceThatExecutesOnTheUsersMachine() {
        // F-38 / SF-38-05, décision D2 : les Managed Agents exécutent les outils chez le fournisseur.
        // Ouvrir une session ici ferait travailler l'agent sur un bac à sable vide pendant que
        // l'utilisateur croit qu'il travaille sur sa machine — le refus doit être explicite.
        Workspace workspace = ws(null);
        workspace.setExecutionTarget(fr.claudegateway.atelier.WorkspaceExecutionTarget.RUNNER);
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service(enabled()).runTask(USER, WORKSPACE, "Corrige le bug."))
                .isInstanceOf(fr.claudegateway.atelier.ExecutionTargetModeException.class);

        verify(provider, never()).createSession(any(), any(), anyList(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void runTaskMountsFilesRunsSessionAndSyncsOutputsInOrder() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("src/a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "src/a.txt")).thenReturn("class A {}");
        when(provider.uploadFile(eq("src_a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
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
        verify(provider).createSession(eq("agent_1"), eq("env_1"), mountsCaptor.capture(), any(), any(), any(), any(), any(), any(), any());
        assertThat(mountsCaptor.getValue()).containsExactly(new FileMount("file_in", "/workspace/src/a.txt"));

        // Sorties réécrites via WorkspaceService (isolation + garde-fous Phase 1).
        verify(workspaceService).writeFile(USER, WORKSPACE, "src/a.txt", "A modifié");
        verify(workspaceService).writeFile(USER, WORKSPACE, "new.txt", "nouveau");
        verify(workspaceService).writeFile(USER, WORKSPACE, "plain.txt", "simple");

        // Ordre : isolation → upload → create → send → await → outputs → download → write.
        // Plus de terminaison en fin de run (F-30 SF-30-04) : la session survit, `idle` non facturée.
        InOrder order = inOrder(workspaceService, provider);
        order.verify(workspaceService).requireOwned(USER, WORKSPACE);
        order.verify(workspaceService).tree(USER, WORKSPACE);
        order.verify(provider).uploadFile(eq("src_a.txt"), any());
        order.verify(provider).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any());
        order.verify(provider).sendUserMessage("sess_1", "Corrige le bug.");
        order.verify(provider).awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any());
        order.verify(provider).listOutputs("sess_1");
        order.verify(provider).downloadFile("out_1");
        verify(provider, never()).terminateSession(any());
    }

    @Test
    void runTaskStreamingNotifiesListenerThenBridgesFilesAndResultLikeRunTask() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("src/a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "src/a.txt")).thenReturn("class A {}");
        when(provider.uploadFile(eq("src_a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        // Le provider relaie des events au listener passé (bridge) puis renvoie la réponse agrégée.
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any())).thenAnswer(inv -> {
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
    void runTaskStreamingKeepsSessionAliveWhenProviderFailsDuringRun() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "a.txt")).thenReturn("x");
        when(provider.uploadFile(eq("a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenThrow(new AgentProviderException("boom"));

        assertThatThrownBy(() -> service(enabled())
                .runTaskStreaming(USER, WORKSPACE, "go", AtelierAgentListener.NOOP))
                .isInstanceOf(AgentProviderException.class);

        // F-30 SF-30-04 : la session n'est plus détruite sur échec — `idle` n'est pas facturée et
        // elle reste réutilisable au message suivant. Sa fin de vie est explicite (resetSession).
        verify(provider, never()).terminateSession(any());
    }

    /** Écouteur applicatif de test enregistrant les notifications reçues. */
    private static final class RecordingAgentListener implements AtelierAgentListener {
        private final List<String> texts = new java.util.ArrayList<>();
        private final List<String> actions = new java.util.ArrayList<>();
        private final List<String> states = new java.util.ArrayList<>();
        private final List<Long> progress = new java.util.ArrayList<>();

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

        @Override
        public void onProgress(long tokens) {
            progress.add(tokens);
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
    void runTaskKeepsSessionAliveWhenProviderFailsDuringRun() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "a.txt")).thenReturn("x");
        when(provider.uploadFile(eq("a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenThrow(new AgentProviderException("boom"));

        assertThatThrownBy(() -> service(enabled()).runTask(USER, WORKSPACE, "go"))
                .isInstanceOf(AgentProviderException.class);

        verify(provider, never()).terminateSession(any());
    }

    @Test
    void runTaskPropagatesTimeoutAndKeepsSessionAlive() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "a.txt")).thenReturn("x");
        when(provider.uploadFile(eq("a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenThrow(new AgentSessionTimeoutException("timeout"));

        assertThatThrownBy(() -> service(enabled()).runTask(USER, WORKSPACE, "go"))
                .isInstanceOf(AgentSessionTimeoutException.class);

        verify(provider, never()).terminateSession(any());
    }

    @Test
    void resolveOutputPathRemapsFlattenedBasenameToOriginalPath() {
        java.util.Set<String> known = java.util.Set.of("src/facture.js", "test.js");
        java.util.Map<String, String> byBasename =
                java.util.Map.of("facture.js", "src/facture.js", "test.js", "test.js");
        // Sortie aplatie « facture.js » (la Files API perd le dossier) → remappée vers « src/facture.js ».
        assertThat(AtelierSessionService.resolveOutputPath("facture.js", known, byBasename))
                .isEqualTo("src/facture.js");
        // Chemin exact déjà connu → conservé tel quel.
        assertThat(AtelierSessionService.resolveOutputPath("test.js", known, byBasename))
                .isEqualTo("test.js");
        // Fichier nouveau et inconnu → écrit tel quel.
        assertThat(AtelierSessionService.resolveOutputPath("README.md", known, byBasename))
                .isEqualTo("README.md");
    }

    // ---------------------------------------------------------------------------------------------
    // Pré-vol quota/plafond et décompte post-run (SF-28-12).
    // ---------------------------------------------------------------------------------------------

    @Test
    void runTaskStreamingRefusesWhenQuotaExhaustedBeforeAnyProviderCall() {
        // Pré-vol quota épuisé : refus AVANT toute création de session (aucun coût).
        doThrow(new QuotaExceededException("épuisé")).when(quotaService).assertWithinQuota(USER);

        assertThatThrownBy(() -> service(enabled())
                .runTaskStreaming(USER, WORKSPACE, "x", AtelierAgentListener.NOOP))
                .isInstanceOf(QuotaExceededException.class);

        verify(workspaceService).requireOwned(USER, WORKSPACE);
        verifyNoInteractions(provider);
        verifyNoInteractions(bootstrapService);
    }

    @Test
    void runTaskStreamingRefusesWhenSandboxLimitReachedBeforeAnyProviderCall() {
        // Quota OK (no-op) mais plafond de bac à sable atteint : refus avant toute session.
        doThrow(new SandboxLimitExceededException("plafond"))
                .when(quotaService).assertWithinSandboxLimit(USER);

        assertThatThrownBy(() -> service(enabled())
                .runTaskStreaming(USER, WORKSPACE, "x", AtelierAgentListener.NOOP))
                .isInstanceOf(SandboxLimitExceededException.class);

        verify(workspaceService).requireOwned(USER, WORKSPACE);
        verifyNoInteractions(provider);
        verifyNoInteractions(bootstrapService);
    }

    @Test
    void runTaskStreamingRecordsTokensAndSandboxSecondsAfterRun() {
        stubNominalRun();
        when(provider.getSessionUsage("sess_1")).thenReturn(new SessionUsage(1_000L, 200L, 8L));

        AtelierSessionResult result = service(enabled())
                .runTaskStreaming(USER, WORKSPACE, "go", AtelierAgentListener.NOOP);

        assertThat(result.reply()).isEqualTo("Terminé.");
        // Décompte : tokens sur le quota, secondes de bac à sable sur le plafond.
        verify(quotaService).recordUsage(USER, 1_000, 200);
        verify(quotaService).recordSandboxSeconds(USER, 8L);
    }

    @Test
    void runTaskStreamingCompletesEvenWhenGetSessionUsageFails() {
        stubNominalRun();
        // Échec de récupération d'usage : best-effort, le run est déjà livré (aucune exception).
        when(provider.getSessionUsage("sess_1"))
                .thenThrow(new AgentProviderException("usage indisponible"));

        AtelierSessionResult result = service(enabled())
                .runTaskStreaming(USER, WORKSPACE, "go", AtelierAgentListener.NOOP);

        assertThat(result.reply()).isEqualTo("Terminé.");
        verify(provider, never()).terminateSession(any());
    }

    /** Stubs communs d'un run nominal sans sortie (le pont fichiers n'est pas l'objet de ces tests). */
    private void stubNominalRun() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "a.txt")).thenReturn("x");
        when(provider.uploadFile(eq("a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());
    }

    @Test
    void uploadFilenameFlattensForbiddenCharacters() {
        // La Files API d'Anthropic rejette « / » (et autres) dans le nom : on aplatit à l'upload.
        assertThat(AtelierSessionService.uploadFilename("src/facture.js")).isEqualTo("src_facture.js");
        assertThat(AtelierSessionService.uploadFilename("a/b/c.txt")).isEqualTo("a_b_c.txt");
        assertThat(AtelierSessionService.uploadFilename("plain.txt")).isEqualTo("plain.txt");
        assertThat(AtelierSessionService.uploadFilename("na me!.md")).isEqualTo("na_me_.md");
    }
    // ---------------------------------------------------------------------------------------------
    // Session persistante par workspace (F-30 SF-30-04, ADR-014).
    // ---------------------------------------------------------------------------------------------

    @Test
    void secondRunReusesTheSessionWithoutRemountingFiles() {
        // Remonter les fichiers écraserait ce que l'agent a fait au tour précédent : la sandbox porte
        // désormais l'état de vérité.
        Workspace workspace = ws(null);
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "a.txt")).thenReturn("x");
        when(provider.uploadFile(eq("a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());

        AtelierSessionService service = service(enabled());
        service.runTask(USER, WORKSPACE, "npm install");
        service.runTask(USER, WORKSPACE, "npm test");

        // Une seule session ouverte, et aucun remontage au second tour.
        verify(provider, times(1)).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any());
        verify(provider, times(1)).uploadFile(eq("a.txt"), any());
        verify(provider).sendUserMessage("sess_1", "npm install");
        verify(provider).sendUserMessage("sess_1", "npm test");
        assertThat(workspace.getAgentSessionId()).isEqualTo("sess_1");
    }

    @Test
    void unusableStoredSessionIsReplacedOnceAndTheMessageIsReplayed() {
        Workspace workspace = ws("sess_morte");
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "a.txt")).thenReturn("x");
        when(provider.uploadFile(eq("a.txt"), any())).thenReturn("file_in");
        // La session persistée n'est plus jouable (expirée / inconnue côté fournisseur).
        doThrow(new AgentProviderException("session inconnue"))
                .when(provider).sendUserMessage(eq("sess_morte"), any());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_neuve"));
        when(provider.awaitCompletion(eq("sess_neuve"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_neuve")).thenReturn(List.of());

        AtelierSessionResult result = service(enabled()).runTask(USER, WORKSPACE, "go");

        assertThat(result.reply()).isEqualTo("Terminé.");
        verify(provider).sendUserMessage("sess_neuve", "go");
        verify(provider, times(1)).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any());
        assertThat(workspace.getAgentSessionId()).isEqualTo("sess_neuve");
    }

    @Test
    void aFailingRetryPropagatesInsteadOfLoopingForever() {
        // Boucler au-delà d'une reprise masquerait une panne réelle du fournisseur.
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_morte"));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_neuve"));
        doThrow(new AgentProviderException("boom")).when(provider).sendUserMessage(any(), any());

        assertThatThrownBy(() -> service(enabled()).runTask(USER, WORKSPACE, "go"))
                .isInstanceOf(AgentProviderException.class);

        verify(provider, times(1)).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void usageIsRecordedAsDeltaNotAsCumulativeTotal() {
        // getSessionUsage renvoie un CUMUL : recréditer ce cumul à chaque tour ferait payer
        // plusieurs fois la même consommation.
        Workspace workspace = ws(null);
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());
        when(provider.getSessionUsage("sess_1"))
                .thenReturn(new SessionUsage(1_000L, 200L, 8L))
                .thenReturn(new SessionUsage(1_500L, 260L, 20L));

        AtelierSessionService service = service(enabled());
        service.runTask(USER, WORKSPACE, "un");
        service.runTask(USER, WORKSPACE, "deux");

        verify(quotaService).recordUsage(USER, 1_000, 200);
        verify(quotaService).recordSandboxSeconds(USER, 8L);
        // Second tour : seul l'écart est décompté, pas le cumul.
        verify(quotaService).recordUsage(USER, 500, 60);
        verify(quotaService).recordSandboxSeconds(USER, 12L);
    }

    @Test
    void usageNeverCreditsNegativeValuesWhenTheCounterGoesBackwards() {
        Workspace workspace = ws(null);
        workspace.setAgentInputTokens(5_000L);
        workspace.setAgentOutputTokens(900L);
        workspace.setAgentActiveSeconds(60L);
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());
        // Session neuve : les compteurs repartent à zéro, donc en deçà du dernier relevé.
        when(provider.getSessionUsage("sess_1")).thenReturn(new SessionUsage(10L, 2L, 1L));

        service(enabled()).runTask(USER, WORKSPACE, "go");

        // Ouvrir une session remet les compteurs à zéro : le delta est le relevé lui-même, jamais négatif.
        verify(quotaService).recordUsage(USER, 10, 2);
        verify(quotaService).recordSandboxSeconds(USER, 1L);
    }

    @Test
    void alreadySyncedOutputsAreNotRewrittenOnTheNextTurn() {
        // Une session persistante réexpose toutes ses sorties à chaque tour : sans registre, chaque
        // tour réécrirait tout le workspace et signalerait des fichiers intacts comme modifiés.
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "a.txt")).thenReturn("x");
        when(provider.uploadFile(eq("a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_1"))
                .thenReturn(List.of(new OutputFile("out_1", "a.txt")))
                .thenReturn(List.of(new OutputFile("out_1", "a.txt"), new OutputFile("out_2", "b.txt")));
        when(provider.downloadFile("out_1")).thenReturn("v1".getBytes());
        when(provider.downloadFile("out_2")).thenReturn("v2".getBytes());

        AtelierSessionService service = service(enabled());
        AtelierSessionResult first = service.runTask(USER, WORKSPACE, "un");
        AtelierSessionResult second = service.runTask(USER, WORKSPACE, "deux");

        assertThat(first.changedFiles()).containsExactly("a.txt");
        // Seule la NOUVELLE sortie est rapatriée au second tour.
        assertThat(second.changedFiles()).containsExactly("b.txt");
        verify(provider, times(1)).downloadFile("out_1");
    }

    @Test
    void resetSessionTerminatesAndClearsTheStoredIdentifier() {
        Workspace workspace = ws("sess_1");
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);

        service(enabled()).resetSession(USER, WORKSPACE);

        verify(provider).terminateSession("sess_1");
        assertThat(workspace.getAgentSessionId()).isNull();
        verify(workspaceRepository).save(workspace);
    }

    @Test
    void resetSessionClearsTheIdentifierEvenWhenTerminationFails() {
        // Sinon le workspace resterait collé à une session injouable.
        Workspace workspace = ws("sess_1");
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        doThrow(new AgentProviderException("indisponible")).when(provider).terminateSession("sess_1");

        service(enabled()).resetSession(USER, WORKSPACE);

        assertThat(workspace.getAgentSessionId()).isNull();
        verify(workspaceRepository).save(workspace);
    }

    @Test
    void resetSessionChecksOwnershipFirstAndNeverCallsTheProviderWhenNotOwned() {
        when(workspaceService.requireOwned(USER, WORKSPACE))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable."));

        assertThatThrownBy(() -> service(enabled()).resetSession(USER, WORKSPACE))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verifyNoInteractions(provider);
    }

    @Test
    void resetSessionIsANoOpWhenNoSessionIsStored() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));

        service(enabled()).resetSession(USER, WORKSPACE);

        verifyNoInteractions(provider);
        verifyNoInteractions(workspaceRepository);
    }
    // ---------------------------------------------------------------------------------------------
    // Consommation du tour exposée au résultat (F-30 SF-30-05).
    // ---------------------------------------------------------------------------------------------

    @Test
    void resultCarriesTheTurnUsageActuallyChargedNotTheSessionTotal() {
        Workspace workspace = ws(null);
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());
        when(provider.getSessionUsage("sess_1"))
                .thenReturn(new SessionUsage(1_000L, 200L, 8L))
                .thenReturn(new SessionUsage(1_500L, 260L, 20L));

        AtelierSessionService service = service(enabled());
        AtelierSessionResult first = service.runTask(USER, WORKSPACE, "un");
        AtelierSessionResult second = service.runTask(USER, WORKSPACE, "deux");

        assertThat(first.inputTokens()).isEqualTo(1_000L);
        assertThat(first.outputTokens()).isEqualTo(200L);
        assertThat(first.activeSeconds()).isEqualTo(8L);
        // Second tour : l'écart, jamais le cumul — et exactement ce qui est décompté du quota.
        assertThat(second.inputTokens()).isEqualTo(500L);
        assertThat(second.outputTokens()).isEqualTo(60L);
        assertThat(second.activeSeconds()).isEqualTo(12L);
        verify(quotaService).recordUsage(USER, 500, 60);
    }

    @Test
    void resultReportsUnknownUsageWhenTheReadingFailsWithoutBreakingTheRun() {
        stubNominalRun();
        when(provider.getSessionUsage("sess_1")).thenThrow(new AgentProviderException("indisponible"));

        AtelierSessionResult result = service(enabled()).runTask(USER, WORKSPACE, "go");

        // Best-effort inchangé : le run aboutit, la consommation est simplement inconnue (zéro).
        assertThat(result.reply()).isEqualTo("Terminé.");
        assertThat(result.inputTokens()).isZero();
        assertThat(result.outputTokens()).isZero();
        assertThat(result.activeSeconds()).isZero();
    }
    // ---------------------------------------------------------------------------------------------
    // Historisation des tours du mode Terminal (F-30 SF-30-09).
    // ---------------------------------------------------------------------------------------------

    @Test
    void aCompletedRunPersistsTheRequestTheReplyAndItsTranscript() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any())).thenAnswer(inv -> {
            ManagedEventListener sink = inv.getArgument(3);
            sink.onAction("bash", "tu_1", "npm test");
            sink.onActionResult("bash", "tu_1", "12 passing", false);
            return new SessionRun("Tests verts.", "end_turn");
        });
        when(provider.listOutputs("sess_1")).thenReturn(List.of());

        service(enabled()).runTask(USER, WORKSPACE, "lance les tests");

        ArgumentCaptor<fr.claudegateway.atelier.AtelierMessage> saved =
                ArgumentCaptor.forClass(fr.claudegateway.atelier.AtelierMessage.class);
        verify(messageRepository, times(2)).save(saved.capture());
        var messages = saved.getAllValues();
        assertThat(messages.get(0).getRole()).isEqualTo("USER");
        assertThat(messages.get(0).getContent()).isEqualTo("lance les tests");
        assertThat(messages.get(0).getUserId()).isEqualTo(USER);
        assertThat(messages.get(1).getRole()).isEqualTo("ASSISTANT");
        assertThat(messages.get(1).getContent()).isEqualTo("Tests verts.");
        // La transcription vient des events du fournisseur, pas d'une déclaration du client.
        assertThat(messages.get(1).getTerminalJson()).contains("npm test").contains("12 passing");
    }

    @Test
    void aPersistedTurnKeepsTheThreadEachCommandCameFrom() {
        // F-35 SF-35-02 : sans le fil dans la transcription, un rechargement de page perdrait les
        // sous-tâches et réafficherait un flux entrelacé illisible.
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any())).thenAnswer(inv -> {
            ManagedEventListener sink = inv.getArgument(3);
            sink.onAction("bash", "tu_1", "grep -r TODO", "thr_sub");
            sink.onActionResult("bash", "tu_1", "3 occurrences", false, "thr_sub");
            return new SessionRun("Fait.", "end_turn");
        });
        when(provider.listOutputs("sess_1")).thenReturn(List.of());

        service(enabled()).runTask(USER, WORKSPACE, "audite le projet");

        ArgumentCaptor<fr.claudegateway.atelier.AtelierMessage> saved =
                ArgumentCaptor.forClass(fr.claudegateway.atelier.AtelierMessage.class);
        verify(messageRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getTerminalJson()).contains("\"threadId\":\"thr_sub\"");
    }

    @Test
    void aRelayedCommandCarriesItsThreadToTheApplicationListener() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any())).thenAnswer(inv -> {
            ManagedEventListener sink = inv.getArgument(3);
            sink.onAction("bash", "tu_1", "npm test", "thr_main");
            sink.onActionResult("bash", "tu_1", "ok", false, "thr_main");
            return new SessionRun("Fait.", "end_turn");
        });
        when(provider.listOutputs("sess_1")).thenReturn(List.of());

        List<String> relayed = new java.util.ArrayList<>();
        service(enabled()).runTaskStreaming(USER, WORKSPACE, "go", new AtelierAgentListener() {
            @Override
            public void onAction(String tool, String toolUseId, String detail, String threadId) {
                relayed.add("action:" + threadId);
            }

            @Override
            public void onActionResult(String tool, String toolUseId, String output, boolean error,
                    String threadId) {
                relayed.add("result:" + threadId);
            }
        });

        assertThat(relayed).containsExactly("action:thr_main", "result:thr_main");
    }

    @Test
    void aFailedRunPersistsNothing() {
        // L'écran annonce déjà que rien n'a été enregistré : persister le contredirait.
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenThrow(new AgentProviderException("boom"));

        assertThatThrownBy(() -> service(enabled()).runTask(USER, WORKSPACE, "go"))
                .isInstanceOf(AgentProviderException.class);

        verifyNoInteractions(messageRepository);
    }

    @Test
    void aRunWithoutAnyCommandIsPersistedWithoutTranscript() {
        stubNominalRun();

        service(enabled()).runTask(USER, WORKSPACE, "bonjour");

        ArgumentCaptor<fr.claudegateway.atelier.AtelierMessage> saved =
                ArgumentCaptor.forClass(fr.claudegateway.atelier.AtelierMessage.class);
        verify(messageRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getTerminalJson()).isNull();
    }

    @Test
    void aFailingHistoryWriteNeverBreaksAnAlreadyDeliveredRun() {
        stubNominalRun();
        doThrow(new RuntimeException("base indisponible")).when(messageRepository).save(any());

        AtelierSessionResult result = service(enabled()).runTask(USER, WORKSPACE, "go");

        assertThat(result.reply()).isEqualTo("Terminé.");
    }

    // -------------------------------------- F-31 / SF-31-02 : session sur un dépôt Git

    /** Workspace adossé à un dépôt, sans session ouverte. */
    private Workspace gitWs() {
        Workspace workspace = ws(null);
        workspace.setSource(fr.claudegateway.atelier.WorkspaceSource.GIT);
        workspace.setGitRepoUrl("https://github.com/octocat/hello");
        workspace.setGitOwner("octocat");
        workspace.setGitRepo("hello");
        workspace.setGitBranch("main");
        return workspace;
    }

    @Test
    void aGitWorkspaceMountsTheRepositoryAndUploadsNothing() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWs());
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_git"));
        when(provider.awaitCompletion(eq("sess_git"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_git")).thenReturn(List.of());

        service(enabled()).runTask(USER, WORKSPACE, "Corrige le bug.");

        ArgumentCaptor<RepositoryMount> repo = ArgumentCaptor.forClass(RepositoryMount.class);
        ArgumentCaptor<List<FileMount>> files = ArgumentCaptor.forClass(List.class);
        verify(provider).createSession(eq("agent_1"), eq("env_1"), files.capture(), repo.capture(), any(),
                any(), any(), any(), any(), any());
        assertThat(files.getValue()).isEmpty();
        assertThat(repo.getValue().url()).isEqualTo("https://github.com/octocat/hello");
        assertThat(repo.getValue().branch()).isEqualTo("main");
        assertThat(repo.getValue().mountPath()).isEqualTo("/workspace");
        assertThat(repo.getValue().authorizationToken()).isEqualTo("github_pat_secret");

        // Aucun fichier téléversé : le plafond `maxSessionFiles` ne s'applique plus (ADR-015).
        verify(provider, never()).uploadFile(any(), any());
    }

    @Test
    void aGitSessionDeclaresTheMcpServerWhenAVaultIsAvailable() {
        // F-31 / SF-31-05 : le vault s'attache À L'OUVERTURE, le fournisseur refuse de l'ajouter
        // ensuite. Sans lui, l'agent n'aurait jamais l'outil de création de pull request.
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWs());
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(mcpVaultService.resolveAccess(USER)).thenReturn(
                Optional.of(new McpAccess("vlt_1", "github", "https://api.githubcopilot.com/mcp/")));
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_git"));
        when(provider.awaitCompletion(eq("sess_git"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_git")).thenReturn(List.of());

        service(enabled()).runTask(USER, WORKSPACE, "go");

        ArgumentCaptor<McpAccess> mcp = ArgumentCaptor.forClass(McpAccess.class);
        verify(provider).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(),
                mcp.capture(), any(), any(), any());
        assertThat(mcp.getValue().vaultId()).isEqualTo("vlt_1");
        assertThat(mcp.getValue().serverName()).isEqualTo("github");
    }

    @Test
    void aGitSessionOpensWithoutMcpWhenNoVaultIsAvailable() {
        // Dégradation volontaire : mieux vaut un Atelier sans création de pull request qu'un Atelier
        // qui refuse d'ouvrir une session (le repli SF-31-04 reste en place).
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWs());
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(mcpVaultService.resolveAccess(USER)).thenReturn(Optional.empty());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_git"));
        when(provider.awaitCompletion(eq("sess_git"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_git")).thenReturn(List.of());

        service(enabled()).runTask(USER, WORKSPACE, "go");

        ArgumentCaptor<McpAccess> mcp = ArgumentCaptor.forClass(McpAccess.class);
        verify(provider).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(),
                mcp.capture(), any(), any(), any());
        assertThat(mcp.getValue()).isNull();
    }

    @Test
    void anArchiveSessionNeverResolvesAnMcpVault() {
        // Un projet d'archive n'a pas de dépôt : lui attacher un vault GitHub n'aurait aucun sens, et
        // déposerait le jeton chez le fournisseur pour rien.
        stubNominalRun();

        service(enabled()).runTask(USER, WORKSPACE, "go");

        verify(mcpVaultService, never()).resolveAccess(any());
    }

    @Test
    void aRepositoryMountNeverRendersItsTokenInATrace() {
        // Le montage porte un secret en mémoire : il ne doit jamais fuir dans un log ou un message.
        RepositoryMount mount = new RepositoryMount(
                "https://github.com/octocat/hello", "github_pat_secret", "/workspace", "main");

        assertThat(mount.toString()).doesNotContain("github_pat_secret").contains("***");
    }

    @Test
    void aGitWorkspaceWithoutTokenNeverOpensASession() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWs());
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(enabled()).runTask(USER, WORKSPACE, "go"))
                .isInstanceOf(fr.claudegateway.git.GitTokenMissingException.class);

        verify(provider, never()).createSession(any(), any(), anyList(), any(), any(), any(), any(), any(), any(), any());
        verify(provider, never()).createSession(any(), any(), anyList(), any(), any(), any(), any(), any(), any(), any());
    }

    // ------------------------ F-31 / SF-31-04 : tour dans la session existante uniquement

    @Test
    void runningInAnExistingSessionRefusesToOpenANewOne() {
        Workspace workspace = gitWs(); // aucune session ouverte
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);

        assertThatThrownBy(() -> service(enabled()).runInExistingSession(USER, WORKSPACE, "publie"))
                .isInstanceOf(NoActiveSessionException.class);

        // Une session neuve repartirait d'un clone vierge : elle publierait une branche vide.
        verify(provider, never()).createSession(any(), any(), anyList(), any(), any(), any(), any(), any(), any(), any());
        verify(provider, never()).createSession(any(), any(), anyList(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void runningInAnExistingSessionUsesItWithoutRemounting() {
        Workspace workspace = gitWs();
        workspace.setAgentSessionId("sess_git");
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(provider.awaitCompletion(eq("sess_git"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Branche poussée.", "end_turn"));
        when(provider.listOutputs("sess_git")).thenReturn(List.of());

        AtelierSessionResult result = service(enabled()).runInExistingSession(USER, WORKSPACE, "publie");

        assertThat(result.reply()).isEqualTo("Branche poussée.");
        verify(provider).sendUserMessage("sess_git", "publie");
        verify(provider, never()).createSession(any(), any(), anyList(), any(), any(), any(), any(), any(), any(), any());
        verify(provider, never()).createSession(any(), any(), anyList(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aDeadSessionIsForgottenRatherThanReplacedDuringAPush() {
        Workspace workspace = gitWs();
        workspace.setAgentSessionId("sess_dead");
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        doThrow(new AgentProviderException("session inconnue"))
                .when(provider).sendUserMessage(eq("sess_dead"), any());

        assertThatThrownBy(() -> service(enabled()).runInExistingSession(USER, WORKSPACE, "publie"))
                .isInstanceOf(NoActiveSessionException.class);

        assertThat(workspace.getAgentSessionId()).isNull();
        verify(provider, never()).createSession(any(), any(), anyList(), any(), any(), any(), any(), any(), any(), any());
        verify(provider, never()).createSession(any(), any(), anyList(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anArchiveWorkspaceNeverResolvesAGitToken() {
        stubNominalRun();

        service(enabled()).runTask(USER, WORKSPACE, "go");

        verifyNoInteractions(gitTokenService);
    }
    // ---------------------------------------------------------------------------------------------
    // Interruption d'un run en cours (F-32 / SF-32-01).
    // ---------------------------------------------------------------------------------------------

    @Test
    void interruptingRelaysTheInterruptEventToTheRunningSession() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_1"));

        service(enabled()).interruptSession(USER, WORKSPACE);

        verify(provider).interruptSession("sess_1");
    }

    @Test
    void interruptingBroadcastsTheMarkToPeerPodsBeforeRelayingToTheProvider() {
        // F-38 / SF-38-13 : le run en vol peut tourner sur un autre pod que celui qui reçoit ce
        // clic. La marque est diffusée AVANT le relais fournisseur, comme elle est posée localement.
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_1"));

        service(enabled()).interruptSession(USER, WORKSPACE);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(relayBroadcaster, provider);
        order.verify(relayBroadcaster).broadcastSessionInterrupt("sess_1", true);
        order.verify(provider).interruptSession("sess_1");
        verify(relayBroadcaster, org.mockito.Mockito.never())
                .broadcastSessionInterrupt("sess_1", false);
    }

    @Test
    void aRefusedInterruptRetractsTheMarkOnPeerPodsToo() {
        // Le rattrapage vaut pour tous les pods : sans lui, un pair afficherait comme interrompu un
        // tour qui ne l'a pas été.
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_1"));
        doThrow(new AgentProviderException("session morte")).when(provider).interruptSession("sess_1");

        assertThatThrownBy(() -> service(enabled()).interruptSession(USER, WORKSPACE))
                .isInstanceOf(AgentProviderException.class);

        verify(relayBroadcaster).broadcastSessionInterrupt("sess_1", true);
        verify(relayBroadcaster).broadcastSessionInterrupt("sess_1", false);
    }

    @Test
    void interruptingChecksOwnershipBeforeCallingTheProvider() {
        // Isolation d'abord : un workspace d'un autre utilisateur n'engage aucun appel fournisseur.
        when(workspaceService.requireOwned(USER, WORKSPACE))
                .thenThrow(new WorkspaceNotFoundException("inconnu"));

        assertThatThrownBy(() -> service(enabled()).interruptSession(USER, WORKSPACE))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verifyNoInteractions(provider);
        // Isolation : rien ne part non plus sur le réseau interne (F-38 / SF-38-13).
        verifyNoInteractions(relayBroadcaster);
    }

    @Test
    void interruptingWithoutAnyRunningSessionIsRefusedWithoutCallingTheProvider() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));

        assertThatThrownBy(() -> service(enabled()).interruptSession(USER, WORKSPACE))
                .isInstanceOf(NoActiveSessionException.class);

        verifyNoInteractions(provider);
    }

    @Test
    void anInterruptedTurnIsReportedAsInterrupted() {
        AtelierSessionService service = service(enabled());
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_1"));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());
        // L'interruption arrive pendant le run (autre thread) : ici, depuis le stub d'attente.
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any())).thenAnswer(inv -> {
            service.interruptSession(USER, WORKSPACE);
            return new SessionRun("Arrêté.", "end_turn");
        });

        AtelierSessionResult result = service.runTask(USER, WORKSPACE, "installe tout");

        assertThat(result.interrupted()).isTrue();
        assertThat(result.reply()).isEqualTo("Arrêté.");
    }

    @Test
    void anInterruptedTurnIsPersistedAndItsUsageCounted() {
        AtelierSessionService service = service(enabled());
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_1"));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());
        when(provider.getSessionUsage("sess_1")).thenReturn(new SessionUsage(900L, 100L, 42L));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any())).thenAnswer(inv -> {
            ManagedEventListener sink = inv.getArgument(3);
            sink.onAction("bash", "tu_1", "npm install");
            service.interruptSession(USER, WORKSPACE);
            return new SessionRun("Arrêté.", "end_turn");
        });

        AtelierSessionResult result = service.runTask(USER, WORKSPACE, "installe tout");

        // Le tour a réellement consommé du bac à sable : il est décompté comme tout autre tour (D3).
        assertThat(result.activeSeconds()).isEqualTo(42L);
        verify(quotaService).recordUsage(USER, 900, 100);
        verify(quotaService).recordSandboxSeconds(USER, 42L);
        // ... et conservé, avec sa transcription partielle et sa marque (D2).
        ArgumentCaptor<fr.claudegateway.atelier.AtelierMessage> saved =
                ArgumentCaptor.forClass(fr.claudegateway.atelier.AtelierMessage.class);
        verify(messageRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getTerminalJson())
                .contains("npm install").contains("\"interrupted\":true");
    }

    @Test
    void anInterruptedTurnWithoutAnyCommandStillCarriesTheMark() {
        // Sans document, la mention « interrompu » serait perdue au rechargement de l'historique.
        AtelierSessionService service = service(enabled());
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_1"));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any())).thenAnswer(inv -> {
            service.interruptSession(USER, WORKSPACE);
            return new SessionRun("Arrêté.", "end_turn");
        });

        service.runTask(USER, WORKSPACE, "go");

        ArgumentCaptor<fr.claudegateway.atelier.AtelierMessage> saved =
                ArgumentCaptor.forClass(fr.claudegateway.atelier.AtelierMessage.class);
        verify(messageRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getTerminalJson()).contains("\"interrupted\":true");
    }

    @Test
    void aFailedInterruptLeavesTheTurnUnmarked() {
        AtelierSessionService service = service(enabled());
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_1"));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());
        doThrow(new AgentProviderException("session morte")).when(provider).interruptSession("sess_1");
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any())).thenAnswer(inv -> {
            assertThatThrownBy(() -> service.interruptSession(USER, WORKSPACE))
                    .isInstanceOf(AgentProviderException.class);
            return new SessionRun("Terminé.", "end_turn");
        });

        AtelierSessionResult result = service.runTask(USER, WORKSPACE, "go");

        // La demande n'est pas passée : afficher le tour comme interrompu serait faux.
        assertThat(result.interrupted()).isFalse();
    }

    @Test
    void anInterruptRequestedOutsideARunNeverMarksTheNextTurn() {
        AtelierSessionService service = service(enabled());
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_1"));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));

        service.interruptSession(USER, WORKSPACE); // session idle : rien à interrompre en vol
        AtelierSessionResult result = service.runTask(USER, WORKSPACE, "go");

        assertThat(result.interrupted()).isFalse();
    }

    @Test
    void aProviderReportedInterruptStopReasonMarksTheTurn() {
        // Repli multi-instance : l'interruption a pu être traitée par une autre réplique.
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_1"));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Arrêté.", "user_interrupt"));

        AtelierSessionResult result = service(enabled()).runTask(USER, WORKSPACE, "go");

        assertThat(result.interrupted()).isTrue();
    }

    @Test
    void aNominalTurnIsNeverReportedAsInterrupted() {
        stubNominalRun();

        AtelierSessionResult result = service(enabled()).runTask(USER, WORKSPACE, "go");

        assertThat(result.interrupted()).isFalse();
    }

    // ------------------------ F-34 / SF-34-01 : instructions portées par le projet

    /** Ouvre une session sur un workspace d'archive vide et renvoie la surcharge de prompt reçue. */
    private String systemSentAtSessionOpening() {
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(provider).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), system.capture(), any(), any(), any(), any(), any());
        return system.getValue();
    }

    @Test
    void projectInstructionsAreAppendedToThePlatformPromptAtSessionOpening() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(instructionsService.resolve(eq(USER), any())).thenReturn(Optional.of(
                new fr.claudegateway.atelier.ProjectInstructions(
                        "CLAUDE.md", "Les tests se lancent avec `make test`.", false)));
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());

        service(enabled()).runTask(USER, WORKSPACE, "go");

        String system = systemSentAtSessionOpening();
        // Le prompt plateforme reste EN TÊTE : les instructions du projet s'ajoutent, elles ne
        // remplacent pas (décision D2 du cadrage — protection contre l'injection).
        assertThat(system).startsWith(AgentSystemPrompt.platform());
        assertThat(system).contains("Les tests se lancent avec `make test`.");
    }

    @Test
    void aProjectWithoutInstructionsOpensItsSessionWithoutAnyOverride() {
        stubNominalRun();

        service(enabled()).runTask(USER, WORKSPACE, "go");

        // Aucune surcharge : la création de session est exactement celle d'avant F-34.
        assertThat(systemSentAtSessionOpening()).isNull();
    }

    @Test
    void aGitProjectCarriesItsInstructionsAlongsideTheRepositoryMount() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWs());
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(instructionsService.resolve(eq(USER), any())).thenReturn(Optional.of(
                new fr.claudegateway.atelier.ProjectInstructions(
                        ".atelier/instructions.md", "Ne touche jamais au dossier legacy/.", false)));
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_git"));
        when(provider.awaitCompletion(eq("sess_git"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_git")).thenReturn(List.of());

        service(enabled()).runTask(USER, WORKSPACE, "go");

        ArgumentCaptor<RepositoryMount> repo = ArgumentCaptor.forClass(RepositoryMount.class);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(provider).createSession(
                eq("agent_1"), eq("env_1"), anyList(), repo.capture(), system.capture(), any(), any(), any(), any(), any());
        assertThat(repo.getValue().url()).isEqualTo("https://github.com/octocat/hello");
        assertThat(system.getValue()).contains("Ne touche jamais au dossier legacy/.");
    }

    @Test
    void instructionsAreReadOnceAtOpeningAndNotOnAReusedSession() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_1"));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());

        service(enabled()).runTask(USER, WORKSPACE, "go");

        // La session persistante fige son prompt à l'ouverture (D5) : rien n'est relu entre les tours.
        verifyNoInteractions(instructionsService);
    }

    // ------------------------ F-33 / SF-33-01 : demander avant d'exécuter

    /** Politique d'outils transmise à l'ouverture de session. */
    private SessionPermissions permissionsSentAtSessionOpening() {
        ArgumentCaptor<SessionPermissions> permissions = ArgumentCaptor.forClass(SessionPermissions.class);
        verify(provider).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(),
                permissions.capture(), any(), any(), any(), any());
        return permissions.getValue();
    }

    /** Workspace possédé, sans session ouverte, portant l'option de validation. */
    private Workspace askingWs() {
        Workspace workspace = ws(null);
        workspace.setAgentAskBeforeBash(true);
        return workspace;
    }

    @Test
    void aProjectWithoutTheOptionOpensItsSessionWithoutAnyPermissionPolicy() {
        stubNominalRun();

        service(enabled()).runTask(USER, WORKSPACE, "go");

        // Non-régression : la session s'ouvre comme avant F-33, tout s'exécute sans demander.
        assertThat(permissionsSentAtSessionOpening()).isEqualTo(SessionPermissions.ALLOW_ALL);
    }

    @Test
    void aProjectWithTheOptionOpensItsSessionAskingBeforeShellCommands() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(askingWs());
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());

        service(enabled()).runTask(USER, WORKSPACE, "go");

        assertThat(permissionsSentAtSessionOpening().askBeforeShellCommands()).isTrue();
    }

    @Test
    void aGitProjectCarriesItsPermissionPolicyAlongsideTheRepositoryMount() {
        Workspace workspace = gitWs();
        workspace.setAgentAskBeforeBash(true);
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_git"));
        when(provider.awaitCompletion(eq("sess_git"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_git")).thenReturn(List.of());

        service(enabled()).runTask(USER, WORKSPACE, "go");

        ArgumentCaptor<RepositoryMount> repo = ArgumentCaptor.forClass(RepositoryMount.class);
        ArgumentCaptor<SessionPermissions> permissions = ArgumentCaptor.forClass(SessionPermissions.class);
        verify(provider).createSession(eq("agent_1"), eq("env_1"), anyList(), repo.capture(), any(),
                permissions.capture(), any(), any(), any(), any());
        assertThat(repo.getValue().url()).isEqualTo("https://github.com/octocat/hello");
        assertThat(permissions.getValue().askBeforeShellCommands()).isTrue();
    }

    @Test
    void settingTheOptionRequiresOwnershipFirst() {
        when(workspaceService.requireOwned(USER, WORKSPACE))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable"));

        assertThatThrownBy(() -> service(enabled()).setAskBeforeBash(USER, WORKSPACE, true))
                .isInstanceOf(WorkspaceNotFoundException.class);

        // Aucune écriture, aucun appel fournisseur sur un workspace qui n'est pas le sien.
        verifyNoInteractions(workspaceRepository);
        verifyNoInteractions(provider);
    }

    @Test
    void settingTheOptionWithoutAnOpenSessionAppliesImmediately() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));

        AtelierSessionService.AgentConfirmationState state =
                service(enabled()).setAskBeforeBash(USER, WORKSPACE, true);

        assertThat(state.enabled()).isTrue();
        assertThat(state.appliesToCurrentSession()).isTrue();
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(saved.capture());
        assertThat(saved.getValue().isAgentAskBeforeBash()).isTrue();
    }

    @Test
    void settingTheOptionWithAnOpenSessionSaysItDoesNotApplyToIt() {
        // La politique d'outils est figée à l'ouverture : annoncer une protection en vigueur alors
        // qu'elle ne l'est pas serait pire que de ne rien annoncer.
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_1"));

        AtelierSessionService.AgentConfirmationState state =
                service(enabled()).setAskBeforeBash(USER, WORKSPACE, true);

        assertThat(state.enabled()).isTrue();
        assertThat(state.appliesToCurrentSession()).isFalse();
    }

    // ------------------------ F-33 / SF-33-02 : réponse à une demande d'autorisation

    @Test
    void confirmingRequiresOwnershipFirst() {
        when(workspaceService.requireOwned(USER, WORKSPACE))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable"));

        assertThatThrownBy(() -> service(enabled()).confirmToolUse(USER, WORKSPACE, "sevt_1", true, null))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verifyNoInteractions(provider);
    }

    @Test
    void confirmingWithoutAnActiveSessionIsRefusedBeforeAnyProviderCall() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));

        assertThatThrownBy(() -> service(enabled()).confirmToolUse(USER, WORKSPACE, "sevt_1", true, null))
                .isInstanceOf(NoActiveSessionException.class);

        verifyNoInteractions(provider);
    }

    @Test
    void confirmingRelaysTheDecisionAndItsReasonToTheSessionOfTheOwnedWorkspace() {
        // L'identifiant de session n'est jamais accepté du client : il est lu sur le workspace possédé.
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_1"));

        service(enabled()).confirmToolUse(USER, WORKSPACE, "sevt_1", false, "Trop risqué.");

        verify(provider).confirmToolUse("sess_1", "sevt_1", false, "Trop risqué.");
    }

    @Test
    void aConfirmationRequestIsRelayedToTheApplicationListener() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_1"));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any())).thenAnswer(invocation -> {
            ManagedEventListener bridge = invocation.getArgument(3);
            bridge.onConfirmationRequest("bash", "sevt_1", "rm -rf build");
            bridge.onConfirmationResolved("sevt_1", "deny");
            return new SessionRun("Compris.", "end_turn");
        });

        List<String> relayed = new java.util.ArrayList<>();
        AtelierAgentListener listener = new AtelierAgentListener() {
            @Override
            public void onConfirmationRequest(String tool, String confirmationId, String detail) {
                relayed.add("ask:" + tool + ":" + confirmationId + ":" + detail);
            }

            @Override
            public void onConfirmationResolved(String confirmationId, String decision) {
                relayed.add("resolved:" + confirmationId + ":" + decision);
            }
        };
        service(enabled()).runTaskStreaming(USER, WORKSPACE, "go", listener);

        assertThat(relayed).containsExactly("ask:bash:sevt_1:rm -rf build", "resolved:sevt_1:deny");
    }

    // ------------------------------------ F-36 / SF-36-01 : plafond de dépense de la session

    @Test
    void aNewSessionCarriesTheRunSpendingCapWhenTheRemainingQuotaIsLarge() {
        // Quota restant large (12 M tokens ≈ 108 $) : c'est le plafond par run (2 $) qui borne.
        stubNominalRun();

        service(enabled()).runTask(USER, WORKSPACE, "go");

        ArgumentCaptor<SessionBudget> budget = ArgumentCaptor.forClass(SessionBudget.class);
        verify(provider).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(),
                any(), budget.capture(), any(), any());
        assertThat(budget.getValue().amountAsString()).isEqualTo("200");
        assertThat(budget.getValue().currency()).isEqualTo("USD");
    }

    @Test
    void aNewSessionIsCappedByTheRemainingQuotaWhenItIsSmallerThanTheRunCap() {
        // 100 000 tokens restants × 9 $/M = 0,90 $ : en dessous du plafond par run (2 $).
        givenRemainingTokens(100_000L);
        stubNominalRun();

        service(enabled()).runTask(USER, WORKSPACE, "go");

        ArgumentCaptor<SessionBudget> budget = ArgumentCaptor.forClass(SessionBudget.class);
        verify(provider).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(),
                any(), budget.capture(), any(), any());
        assertThat(budget.getValue().amountAsString()).isEqualTo("90");
    }

    @Test
    void anAlmostExhaustedQuotaStillOpensASessionAtTheFloor() {
        // 1 000 tokens restants = 0,009 $ : un budget nul serait refusé par le fournisseur.
        givenRemainingTokens(1_000L);
        stubNominalRun();

        service(enabled()).runTask(USER, WORKSPACE, "go");

        ArgumentCaptor<SessionBudget> budget = ArgumentCaptor.forClass(SessionBudget.class);
        verify(provider).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(),
                any(), budget.capture(), any(), any());
        assertThat(budget.getValue().amountAsString()).isEqualTo("10");
    }

    @Test
    void aReusedSessionGetsNoBudgetBecauseTheProviderRefusesToAddOneAfterwards() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_ouverte"));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(provider.awaitCompletion(eq("sess_ouverte"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_ouverte")).thenReturn(List.of());

        service(enabled()).runTask(USER, WORKSPACE, "go");

        verify(provider, never()).createSession(any(), any(), anyList(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRunStoppedByItsSpendingCapKeepsItsReplyAndIsFlagged() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("J'ai commencé…", "budget_reached"));
        when(provider.listOutputs("sess_1")).thenReturn(List.of(new OutputFile("out_1", "a.txt")));
        when(provider.downloadFile("out_1")).thenReturn("partiel".getBytes());

        AtelierSessionResult result = service(enabled()).runTask(USER, WORKSPACE, "go");

        assertThat(result.budgetReached()).isTrue();
        assertThat(result.interrupted()).isFalse();
        // Le travail déjà produit n'est pas jeté : réponse conservée et fichiers réécrits.
        assertThat(result.reply()).isEqualTo("J'ai commencé…");
        assertThat(result.changedFiles()).containsExactly("a.txt");
    }

    @Test
    void aRunStoppedByItsSpendingCapIsPersistedWithTheFlag() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("J'ai commencé…", "budget_reached"));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());

        service(enabled()).runTask(USER, WORKSPACE, "go");

        ArgumentCaptor<fr.claudegateway.atelier.AtelierMessage> saved =
                ArgumentCaptor.forClass(fr.claudegateway.atelier.AtelierMessage.class);
        verify(messageRepository, times(2)).save(saved.capture());
        // Sans document, la mention disparaîtrait au rechargement de l'écran.
        assertThat(saved.getAllValues().get(1).getTerminalJson()).contains("\"budgetReached\":true");
    }

    @Test
    void aNormalRunIsNeverFlaggedAsHavingReachedItsCap() {
        stubNominalRun();

        AtelierSessionResult result = service(enabled()).runTask(USER, WORKSPACE, "go");

        assertThat(result.budgetReached()).isFalse();
    }

    // ------------------------------------ F-36 / SF-36-02 : décompte au coût réel

    @Test
    void theQuotaIsChargedFromTheRealCostWhenTheProviderReportsIt() {
        stubNominalRun();
        // 90 cents = 0,90 $ ; à 9 $/M et markup 1,0 ⇒ 100 000 tokens équivalents, répartis au prorata
        // des tokens rapportés (1 000 / 200) ⇒ 83 333 en entrée, 16 667 en sortie.
        when(provider.getSessionUsage("sess_1")).thenReturn(new SessionUsage(1_000L, 200L, 8L, 90L));

        AtelierSessionResult result = service(enabled()).runTask(USER, WORKSPACE, "go");

        verify(quotaService).recordUsage(USER, 83_333, 16_667);
        verify(quotaService).recordSandboxSeconds(USER, 8L);
        // Le tour affiche ce qui est réellement décompté : une seule source de vérité.
        assertThat(result.inputTokens()).isEqualTo(83_333L);
        assertThat(result.outputTokens()).isEqualTo(16_667L);
    }

    @Test
    void theMarkupMultipliesWhatIsChargedToTheQuota() {
        stubNominalRun();
        when(provider.getSessionUsage("sess_1")).thenReturn(new SessionUsage(1_000L, 200L, 8L, 90L));

        service(enabled(), costProperties(new java.math.BigDecimal("2.0")))
                .runTask(USER, WORKSPACE, "go");

        // 2× le décompte neutre : le levier de marge agit sur le décompte, pas sur le tarif affiché.
        verify(quotaService).recordUsage(USER, 166_667, 33_333);
    }

    @Test
    void onlyTheCostDeltaIsChargedOnASecondTurnOfTheSameSession() {
        // Le fournisseur rapporte un CUMUL : recréditer le cumul ferait payer deux fois le 1er tour.
        Workspace workspace = ws("sess_ouverte");
        workspace.setAgentInputTokens(1_000L);
        workspace.setAgentOutputTokens(200L);
        workspace.setAgentListCost(90L);
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(provider.awaitCompletion(eq("sess_ouverte"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_ouverte")).thenReturn(List.of());
        when(provider.getSessionUsage("sess_ouverte"))
                .thenReturn(new SessionUsage(2_000L, 400L, 16L, 135L));

        service(enabled()).runTask(USER, WORKSPACE, "go");

        // Delta = 45 cents ⇒ 50 000 tokens, au prorata du delta de tokens (1 000 / 200).
        verify(quotaService).recordUsage(USER, 41_667, 8_333);
        assertThat(workspace.getAgentListCost()).isEqualTo(135L);
    }

    @Test
    void aMissingRealCostFallsBackToTheRawTokenAccounting() {
        // Repli : sans coût rapporté, le décompte est exactement celui d'avant F-36.
        stubNominalRun();
        when(provider.getSessionUsage("sess_1")).thenReturn(new SessionUsage(1_000L, 200L, 8L, null));

        service(enabled()).runTask(USER, WORKSPACE, "go");

        verify(quotaService).recordUsage(USER, 1_000, 200);
    }

    @Test
    void aCostWithoutAnyReportedTokenIsChargedEntirelyOnInput() {
        // Recherches web ou temps de bac à sable seuls : ne rien décompter serait faux.
        stubNominalRun();
        when(provider.getSessionUsage("sess_1")).thenReturn(new SessionUsage(0L, 0L, 30L, 18L));

        service(enabled()).runTask(USER, WORKSPACE, "go");

        verify(quotaService).recordUsage(USER, 20_000, 0);
    }

    @Test
    void aCostReadingBelowThePreviousOneNeverCreditsTheQuota() {
        // Session remplacée côté fournisseur : le cumul repart plus bas. Jamais de delta négatif.
        Workspace workspace = ws("sess_ouverte");
        workspace.setAgentListCost(500L);
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(provider.awaitCompletion(eq("sess_ouverte"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_ouverte")).thenReturn(List.of());
        when(provider.getSessionUsage("sess_ouverte"))
                .thenReturn(new SessionUsage(0L, 0L, 0L, 100L));

        service(enabled()).runTask(USER, WORKSPACE, "go");

        verify(quotaService).recordUsage(USER, 0, 0);
    }

    @Test
    void openingANewSessionResetsTheCostCounterToo() {
        Workspace workspace = ws(null);
        workspace.setAgentListCost(500L);
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_neuve"));
        when(provider.awaitCompletion(eq("sess_neuve"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_neuve")).thenReturn(List.of());
        when(provider.getSessionUsage("sess_neuve")).thenReturn(new SessionUsage(0L, 0L, 0L, 9L));

        service(enabled()).runTask(USER, WORKSPACE, "go");

        // Le cumul de l'ancienne session ne doit pas masquer les premiers tours de la nouvelle.
        verify(quotaService).recordUsage(USER, 10_000, 0);
    }

    // ------------------------------------ F-35 / SF-35-01 : roster de sous-agents

    @Test
    void subagentsAreEnabledByDefaultInTheConfiguration() {
        // Révision D1 (2026-08-26) : une capacité livrée mais éteinte n'est jamais testée. Le défaut
        // est donc « activée » ; le flag reste pour couper sans redéployer.
        AtelierAgentProperties defaults = new AtelierAgentProperties(true, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, Duration.ZERO);

        assertThat(defaults.subagentsEnabled()).isTrue();
        assertThat(defaults.maxSubagents()).isEqualTo(3);
    }

    @Test
    void aDelegatingSessionOpensWithTheCappedSelfRoster() {
        stubNominalRun();

        service(withSubagents(3)).runTask(USER, WORKSPACE, "audite le projet");

        assertThat(capturedDelegation()).isEqualTo(DelegationPolicy.of(true, 3));
    }

    @Test
    void aSessionOpenedWithTheFlagOffCarriesNoDelegationAtAll() {
        stubNominalRun();

        service(enabled()).runTask(USER, WORKSPACE, "audite le projet");

        assertThat(capturedDelegation()).isEqualTo(DelegationPolicy.DISABLED);
    }

    @Test
    void aDelegatingSessionGetsTheRaisedRunSpendingCap() {
        // Une session qui délègue mène plusieurs travaux de front : 5 $ au lieu de 2 $ (F-36 avait
        // laissé la propriété dormante en attendant cette SF).
        stubNominalRun();

        service(withSubagents(3)).runTask(USER, WORKSPACE, "go");

        assertThat(capturedBudget().amountAsString()).isEqualTo("500");
    }

    @Test
    void theRaisedCapIsStillBoundedByTheRemainingQuota() {
        // 100 000 tokens restants × 9 $/M = 0,90 $ : déléguer ne donne jamais accès à plus que ce que
        // l'utilisateur a payé.
        givenRemainingTokens(100_000L);
        stubNominalRun();

        service(withSubagents(3)).runTask(USER, WORKSPACE, "go");

        assertThat(capturedBudget().amountAsString()).isEqualTo("90");
    }

    @Test
    void aGitSessionAlsoCarriesTheDelegationAndTheRaisedCap() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWs());
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(mcpVaultService.resolveAccess(USER)).thenReturn(Optional.empty());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(new ManagedSession("sess_git"));
        when(provider.awaitCompletion(eq("sess_git"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_git")).thenReturn(List.of());

        service(withSubagents(2)).runTask(USER, WORKSPACE, "corrige les tests");

        assertThat(capturedDelegation()).isEqualTo(DelegationPolicy.of(true, 2));
        assertThat(capturedBudget().amountAsString()).isEqualTo("500");
    }

    /** Politique de délégation effectivement transmise au provider à l'ouverture. */
    private DelegationPolicy capturedDelegation() {
        ArgumentCaptor<DelegationPolicy> captor = ArgumentCaptor.forClass(DelegationPolicy.class);
        verify(provider).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(),
                any(), captor.capture(), any());
        return captor.getValue();
    }

    /** Plafond de dépense effectivement transmis au provider à l'ouverture. */
    private SessionBudget capturedBudget() {
        ArgumentCaptor<SessionBudget> captor = ArgumentCaptor.forClass(SessionBudget.class);
        verify(provider).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(),
                captor.capture(), any(), any());
        return captor.getValue();
    }

    // ---------------------------------------------------------------------------------------------
    // Diff des modifications du tour (F-37 / SF-37-01).
    // ---------------------------------------------------------------------------------------------

    /** Run nominal sur une session neuve, avec les sorties données. */
    private void stubRunWithOutputs(List<OutputFile> outputs) {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("src/a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "src/a.txt")).thenReturn("un\ndeux\ntrois\n");
        when(provider.uploadFile(eq("src_a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_1")).thenReturn(outputs);
    }

    @Test
    void aRewrittenFileCarriesTheUnifiedDiffOfWhatChanged() {
        stubRunWithOutputs(List.of(new OutputFile("out_1", "/workspace/src/a.txt")));
        when(provider.downloadFile("out_1")).thenReturn("un\nDEUX\ntrois\n".getBytes());

        AtelierSessionResult result = service(enabled()).runTask(USER, WORKSPACE, "corrige");

        assertThat(result.changedFiles()).containsExactly("src/a.txt");
        assertThat(result.diffs()).hasSize(1);
        FileDiff diff = result.diffs().get(0);
        assertThat(diff.path()).isEqualTo("src/a.txt");
        assertThat(diff.added()).isFalse();
        assertThat(diff.diff()).contains("-deux").contains("+DEUX");
        assertThat(diff.addedLines()).isEqualTo(1);
        assertThat(diff.removedLines()).isEqualTo(1);
        verify(workspaceService).writeFile(USER, WORKSPACE, "src/a.txt", "un\nDEUX\ntrois\n");
    }

    @Test
    void aFileRewrittenIdenticallyIsNeitherWrittenNorAnnouncedAsModified() {
        // Une session persistante réexpose ses sorties : sans ce filtre, un fichier intact serait
        // annoncé comme modifié, avec un diff vide (F-37, décision D5).
        stubRunWithOutputs(List.of(new OutputFile("out_1", "/workspace/src/a.txt")));
        when(provider.downloadFile("out_1")).thenReturn("un\ndeux\ntrois\n".getBytes());

        AtelierSessionResult result = service(enabled()).runTask(USER, WORKSPACE, "ne change rien");

        assertThat(result.changedFiles()).isEmpty();
        assertThat(result.diffs()).isEmpty();
        verify(workspaceService, never()).writeFile(eq(USER), eq(WORKSPACE), eq("src/a.txt"), any());
    }

    @Test
    void aFileThatDidNotExistIsPresentedAsAFullAddition() {
        stubRunWithOutputs(List.of(new OutputFile("out_1", "/mnt/session/outputs/nouveau.txt")));
        when(provider.downloadFile("out_1")).thenReturn("alpha\nbeta\n".getBytes());

        AtelierSessionResult result = service(enabled()).runTask(USER, WORKSPACE, "crée");

        assertThat(result.diffs()).hasSize(1);
        FileDiff diff = result.diffs().get(0);
        assertThat(diff.added()).isTrue();
        assertThat(diff.addedLines()).isEqualTo(2);
        assertThat(diff.removedLines()).isZero();
        assertThat(diff.diff()).contains("+alpha").contains("+beta");
    }

    @Test
    void anUnreadablePreviousVersionDegradesToAnAdditionInsteadOfFailingTheRun() {
        // Le fichier est dans l'arborescence mais sa lecture échoue : un défaut d'affichage ne doit
        // jamais faire échouer un run déjà mené à son terme.
        stubRunWithOutputs(List.of(new OutputFile("out_1", "/workspace/src/a.txt")));
        when(workspaceService.readFile(USER, WORKSPACE, "src/a.txt"))
                .thenReturn("un\ndeux\ntrois\n")
                .thenThrow(new WorkspaceNotFoundException("Fichier introuvable : src/a.txt"));
        when(provider.downloadFile("out_1")).thenReturn("neuf\n".getBytes());

        AtelierSessionResult result = service(enabled()).runTask(USER, WORKSPACE, "corrige");

        assertThat(result.changedFiles()).containsExactly("src/a.txt");
        assertThat(result.diffs()).hasSize(1);
        assertThat(result.diffs().get(0).added()).isTrue();
    }

    @Test
    void theNumberOfDescribedFilesIsBoundedWhileTheChangedListStaysComplete() {
        stubRunWithOutputs(List.of(
                new OutputFile("out_1", "un.txt"),
                new OutputFile("out_2", "deux.txt"),
                new OutputFile("out_3", "trois.txt")));
        when(provider.downloadFile("out_1")).thenReturn("a\n".getBytes());
        when(provider.downloadFile("out_2")).thenReturn("b\n".getBytes());
        when(provider.downloadFile("out_3")).thenReturn("c\n".getBytes());

        AtelierSessionResult result = service(enabled(), costProperties(),
                new AtelierDiffProperties(400, 2)).runTask(USER, WORKSPACE, "génère");

        // Tous les fichiers sont réécrits et listés ; seuls les deux premiers sont décrits.
        assertThat(result.changedFiles()).containsExactly("un.txt", "deux.txt", "trois.txt");
        assertThat(result.diffs()).extracting(FileDiff::path).containsExactly("un.txt", "deux.txt");
        verify(workspaceService).writeFile(USER, WORKSPACE, "trois.txt", "c\n");
    }

    @Test
    void aDiffLongerThanTheBoundIsTruncatedAndSaysHowMuchWasOmitted() {
        StringBuilder generated = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            generated.append("ligne ").append(i).append('\n');
        }
        stubRunWithOutputs(List.of(new OutputFile("out_1", "genere.txt")));
        when(provider.downloadFile("out_1")).thenReturn(generated.toString().getBytes());

        AtelierSessionResult result = service(enabled(), costProperties(),
                new AtelierDiffProperties(10, 50)).runTask(USER, WORKSPACE, "génère");

        FileDiff diff = result.diffs().get(0);
        assertThat(diff.diff().lines()).hasSize(10);
        assertThat(diff.omittedLines()).isEqualTo(91);
    }

    @Test
    void theTurnIsPersistedWithItsDiffs() {
        stubRunWithOutputs(List.of(new OutputFile("out_1", "/workspace/src/a.txt")));
        when(provider.downloadFile("out_1")).thenReturn("un\nDEUX\ntrois\n".getBytes());

        service(enabled()).runTask(USER, WORKSPACE, "corrige");

        ArgumentCaptor<fr.claudegateway.atelier.AtelierMessage> saved =
                ArgumentCaptor.forClass(fr.claudegateway.atelier.AtelierMessage.class);
        verify(messageRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getTerminalJson())
                .contains("\"diffs\"")
                .contains("\"path\":\"src/a.txt\"")
                .contains("+DEUX");
    }

    @Test
    void aTurnWithoutAnyModificationKeepsTheDocumentItHadBeforeF37() {
        // Sans transcription, sans interruption et sans modification, le tour reste sans document :
        // exactement le comportement d'avant F-37.
        stubNominalRun();

        service(enabled()).runTask(USER, WORKSPACE, "bonjour");

        ArgumentCaptor<fr.claudegateway.atelier.AtelierMessage> saved =
                ArgumentCaptor.forClass(fr.claudegateway.atelier.AtelierMessage.class);
        verify(messageRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getTerminalJson()).isNull();
    }

    @Test
    void aBinaryOutputIsWrittenButReportedAsUnreadableRatherThanCompared() {
        stubRunWithOutputs(List.of(new OutputFile("out_1", "image.bin")));
        when(provider.downloadFile("out_1")).thenReturn(new byte[] {80, 75, 0, 3, 4});

        AtelierSessionResult result = service(enabled()).runTask(USER, WORKSPACE, "génère");

        assertThat(result.changedFiles()).containsExactly("image.bin");
        assertThat(result.diffs()).hasSize(1);
        assertThat(result.diffs().get(0).unreadable()).isTrue();
        assertThat(result.diffs().get(0).diff()).isEmpty();
    }
    // ---------------------------------------------------------------------------------------------
    // F-30 / SF-30-13 — ligne vivante : relevé de consommation PENDANT le run
    // ---------------------------------------------------------------------------------------------

    /**
     * Fait battre le polling {@code beats} fois, comme le provider le ferait pendant un run, puis
     * rend une réponse. C'est le seul moyen d'exercer le chemin réel : le battement vient du provider.
     */
    private void answerAfterBeats(int beats) {
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any(), any())).thenAnswer(inv -> {
            ManagedEventListener bridge = inv.getArgument(3);
            for (int i = 0; i < beats; i++) {
                bridge.onPoll();
            }
            return new SessionRun("Terminé.", "end_turn");
        });
    }

    private void givenRunningSession() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_1"));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());
    }

    @Test
    void progressIsRelayedAsTheTurnDeltaNeverTheSessionTotal() {
        givenRunningSession();
        // La session porte déjà 1 000 tokens des tours précédents : c'est la base du tour.
        Workspace withHistory = ws("sess_1");
        withHistory.setAgentInputTokens(600L);
        withHistory.setAgentOutputTokens(400L);
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(withHistory);
        when(provider.getSessionUsage("sess_1")).thenReturn(new SessionUsage(900L, 500L, 0L, null));
        answerAfterBeats(1);

        RecordingAgentListener listener = new RecordingAgentListener();
        service(withProgress(Duration.ofNanos(1))).runTaskStreaming(USER, WORKSPACE, "go", listener);

        // 1400 relevés - 1000 de base = 400 pour CE tour, et non le cumul de la session.
        assertThat(listener.progress).containsExactly(400L);
    }

    @Test
    void progressIsSampledAtMostOncePerConfiguredInterval() {
        givenRunningSession();
        when(provider.getSessionUsage("sess_1")).thenReturn(new SessionUsage(10L, 5L, 0L, null));
        answerAfterBeats(5);

        RecordingAgentListener listener = new RecordingAgentListener();
        // Intervalle d'une heure : les cinq battements se suivent, un seul relevé peut passer.
        service(withProgress(Duration.ofHours(1))).runTaskStreaming(USER, WORKSPACE, "go", listener);

        assertThat(listener.progress).hasSize(1);
        // Deux appels au total : le relevé de progression, et le décompte de FIN de tour, qui est un
        // chemin distinct (SF-30-04) et reste dû quel que soit l'intervalle.
        verify(provider, times(2)).getSessionUsage("sess_1");
    }

    @Test
    void aZeroIntervalDisablesTheSamplingEntirely() {
        givenRunningSession();
        answerAfterBeats(5);

        RecordingAgentListener listener = new RecordingAgentListener();
        // `enabled()` porte Duration.ZERO : aucun relevé, flux strictement identique à avant SF-30-13.
        service(enabled()).runTaskStreaming(USER, WORKSPACE, "go", listener);

        assertThat(listener.progress).isEmpty();
        // Un seul appel : celui du décompte de fin de tour. Aucun relevé de progression n'a eu lieu.
        verify(provider, times(1)).getSessionUsage("sess_1");
    }

    @Test
    void aFailedSamplingIsSwallowedAndTheRunCompletes() {
        givenRunningSession();
        when(provider.getSessionUsage("sess_1")).thenThrow(new AgentProviderException("boom"));
        answerAfterBeats(1);

        RecordingAgentListener listener = new RecordingAgentListener();
        AtelierSessionResult result =
                service(withProgress(Duration.ofNanos(1))).runTaskStreaming(USER, WORKSPACE, "go", listener);

        // Le run a du travail en vol : un indicateur manqué ne doit ni l'interrompre ni le ralentir.
        assertThat(result.reply()).isEqualTo("Terminé.");
        assertThat(listener.progress).isEmpty();
    }

    @Test
    void theProgressCounterNeverGoesBackwards() {
        givenRunningSession();
        // Deuxième relevé plus BAS que le premier (session remplacée côté fournisseur).
        when(provider.getSessionUsage("sess_1"))
                .thenReturn(new SessionUsage(80L, 20L, 0L, null))
                .thenReturn(new SessionUsage(10L, 5L, 0L, null));
        answerAfterBeats(2);

        RecordingAgentListener listener = new RecordingAgentListener();
        service(withProgress(Duration.ofNanos(1))).runTaskStreaming(USER, WORKSPACE, "go", listener);

        // Un compteur qui recule donnerait l'impression que le travail est défait.
        assertThat(listener.progress).containsExactly(100L, 100L);
    }
}
