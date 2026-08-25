package fr.claudegateway.atelier.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.agent.ManagedAgentProvider.SessionUsage;
import fr.claudegateway.quota.QuotaExceededException;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.SandboxLimitExceededException;

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

    private AtelierAgentProperties enabled() {
        return new AtelierAgentProperties(true, null, null, null, null, null, null, null, null, null, null, null);
    }

    private AtelierAgentProperties disabled() {
        return new AtelierAgentProperties(false, null, null, null, null, null, null, null, null, null, null, null);
    }

    private AtelierAgentConfig config() {
        return AtelierAgentConfig.builder()
                .agentId("agent_1").environmentId("env_1").agentVersion("v1").build();
    }

    private AtelierSessionService service(AtelierAgentProperties props) {
        return new AtelierSessionService(provider, workspaceService, bootstrapService, props, quotaService,
                workspaceRepository, messageRepository, gitTokenService, instructionsService);
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
    void runTaskMountsFilesRunsSessionAndSyncsOutputsInOrder() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("src/a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "src/a.txt")).thenReturn("class A {}");
        when(provider.uploadFile(eq("src_a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
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
        verify(provider).createSession(eq("agent_1"), eq("env_1"), mountsCaptor.capture(), any(), any(), any());
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
        order.verify(provider).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any());
        order.verify(provider).sendUserMessage("sess_1", "Corrige le bug.");
        order.verify(provider).awaitCompletion(eq("sess_1"), any(), anyInt(), any());
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
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
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
    void runTaskStreamingKeepsSessionAliveWhenProviderFailsDuringRun() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "a.txt")).thenReturn("x");
        when(provider.uploadFile(eq("a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
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
    void runTaskKeepsSessionAliveWhenProviderFailsDuringRun() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of("a.txt"));
        when(workspaceService.readFile(USER, WORKSPACE, "a.txt")).thenReturn("x");
        when(provider.uploadFile(eq("a.txt"), any())).thenReturn("file_in");
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
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
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
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
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
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
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_1")).thenReturn(List.of());

        AtelierSessionService service = service(enabled());
        service.runTask(USER, WORKSPACE, "npm install");
        service.runTask(USER, WORKSPACE, "npm test");

        // Une seule session ouverte, et aucun remontage au second tour.
        verify(provider, times(1)).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any());
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
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_neuve"));
        when(provider.awaitCompletion(eq("sess_neuve"), any(), anyInt(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_neuve")).thenReturn(List.of());

        AtelierSessionResult result = service(enabled()).runTask(USER, WORKSPACE, "go");

        assertThat(result.reply()).isEqualTo("Terminé.");
        verify(provider).sendUserMessage("sess_neuve", "go");
        verify(provider, times(1)).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any());
        assertThat(workspace.getAgentSessionId()).isEqualTo("sess_neuve");
    }

    @Test
    void aFailingRetryPropagatesInsteadOfLoopingForever() {
        // Boucler au-delà d'une reprise masquerait une panne réelle du fournisseur.
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_morte"));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_neuve"));
        doThrow(new AgentProviderException("boom")).when(provider).sendUserMessage(any(), any());

        assertThatThrownBy(() -> service(enabled()).runTask(USER, WORKSPACE, "go"))
                .isInstanceOf(AgentProviderException.class);

        verify(provider, times(1)).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any());
    }

    @Test
    void usageIsRecordedAsDeltaNotAsCumulativeTotal() {
        // getSessionUsage renvoie un CUMUL : recréditer ce cumul à chaque tour ferait payer
        // plusieurs fois la même consommation.
        Workspace workspace = ws(null);
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
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
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
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
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
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
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
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
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any())).thenAnswer(inv -> {
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
    void aFailedRunPersistsNothing() {
        // L'écran annonce déjà que rien n'a été enregistré : persister le contredirait.
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws(null));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(workspaceService.tree(USER, WORKSPACE)).thenReturn(List.of());
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
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
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_git"));
        when(provider.awaitCompletion(eq("sess_git"), any(), anyInt(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_git")).thenReturn(List.of());

        service(enabled()).runTask(USER, WORKSPACE, "Corrige le bug.");

        ArgumentCaptor<RepositoryMount> repo = ArgumentCaptor.forClass(RepositoryMount.class);
        ArgumentCaptor<List<FileMount>> files = ArgumentCaptor.forClass(List.class);
        verify(provider).createSession(eq("agent_1"), eq("env_1"), files.capture(), repo.capture(), any(), any());
        assertThat(files.getValue()).isEmpty();
        assertThat(repo.getValue().url()).isEqualTo("https://github.com/octocat/hello");
        assertThat(repo.getValue().branch()).isEqualTo("main");
        assertThat(repo.getValue().mountPath()).isEqualTo("/workspace");
        assertThat(repo.getValue().authorizationToken()).isEqualTo("github_pat_secret");

        // Aucun fichier téléversé : le plafond `maxSessionFiles` ne s'applique plus (ADR-015).
        verify(provider, never()).uploadFile(any(), any());
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

        verify(provider, never()).createSession(any(), any(), anyList(), any(), any(), any());
    }

    // ------------------------ F-31 / SF-31-04 : tour dans la session existante uniquement

    @Test
    void runningInAnExistingSessionRefusesToOpenANewOne() {
        Workspace workspace = gitWs(); // aucune session ouverte
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);

        assertThatThrownBy(() -> service(enabled()).runInExistingSession(USER, WORKSPACE, "publie"))
                .isInstanceOf(NoActiveSessionException.class);

        // Une session neuve repartirait d'un clone vierge : elle publierait une branche vide.
        verify(provider, never()).createSession(any(), any(), anyList(), any(), any(), any());
    }

    @Test
    void runningInAnExistingSessionUsesItWithoutRemounting() {
        Workspace workspace = gitWs();
        workspace.setAgentSessionId("sess_git");
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(provider.awaitCompletion(eq("sess_git"), any(), anyInt(), any()))
                .thenReturn(new SessionRun("Branche poussée.", "end_turn"));
        when(provider.listOutputs("sess_git")).thenReturn(List.of());

        AtelierSessionResult result = service(enabled()).runInExistingSession(USER, WORKSPACE, "publie");

        assertThat(result.reply()).isEqualTo("Branche poussée.");
        verify(provider).sendUserMessage("sess_git", "publie");
        verify(provider, never()).createSession(any(), any(), anyList(), any(), any(), any());
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
        verify(provider, never()).createSession(any(), any(), anyList(), any(), any(), any());
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
    void interruptingChecksOwnershipBeforeCallingTheProvider() {
        // Isolation d'abord : un workspace d'un autre utilisateur n'engage aucun appel fournisseur.
        when(workspaceService.requireOwned(USER, WORKSPACE))
                .thenThrow(new WorkspaceNotFoundException("inconnu"));

        assertThatThrownBy(() -> service(enabled()).interruptSession(USER, WORKSPACE))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verifyNoInteractions(provider);
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
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any())).thenAnswer(inv -> {
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
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any())).thenAnswer(inv -> {
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
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any())).thenAnswer(inv -> {
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
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any())).thenAnswer(inv -> {
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
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
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
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
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
        verify(provider).createSession(eq("agent_1"), eq("env_1"), anyList(), any(), system.capture(), any());
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
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
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
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_git"));
        when(provider.awaitCompletion(eq("sess_git"), any(), anyInt(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_git")).thenReturn(List.of());

        service(enabled()).runTask(USER, WORKSPACE, "go");

        ArgumentCaptor<RepositoryMount> repo = ArgumentCaptor.forClass(RepositoryMount.class);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(provider).createSession(
                eq("agent_1"), eq("env_1"), anyList(), repo.capture(), system.capture(), any());
        assertThat(repo.getValue().url()).isEqualTo("https://github.com/octocat/hello");
        assertThat(system.getValue()).contains("Ne touche jamais au dossier legacy/.");
    }

    @Test
    void instructionsAreReadOnceAtOpeningAndNotOnAReusedSession() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(ws("sess_1"));
        when(bootstrapService.ensureBootstrapped()).thenReturn(Optional.of(config()));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
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
                permissions.capture());
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
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_1"));
        when(provider.awaitCompletion(eq("sess_1"), any(), anyInt(), any()))
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
        when(provider.createSession(eq("agent_1"), eq("env_1"), anyList(), any(), any(), any()))
                .thenReturn(new ManagedSession("sess_git"));
        when(provider.awaitCompletion(eq("sess_git"), any(), anyInt(), any()))
                .thenReturn(new SessionRun("Terminé.", "end_turn"));
        when(provider.listOutputs("sess_git")).thenReturn(List.of());

        service(enabled()).runTask(USER, WORKSPACE, "go");

        ArgumentCaptor<RepositoryMount> repo = ArgumentCaptor.forClass(RepositoryMount.class);
        ArgumentCaptor<SessionPermissions> permissions = ArgumentCaptor.forClass(SessionPermissions.class);
        verify(provider).createSession(eq("agent_1"), eq("env_1"), anyList(), repo.capture(), any(),
                permissions.capture());
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
}
