package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Boucle tool-use en <b>cible d'exécution RUNNER</b> (F-38 / SF-38-05).
 *
 * <p>Trois pièges sont vérifiés ici parce qu'ils échouent <b>en silence</b> : un projet Git refusé à
 * tort par le garde-fou du mode Assistant, une consigne système lue dans un stockage objet vide, et
 * une recherche qui rapatrierait tout le projet fichier par fichier au lieu d'un seul appel.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceRunnerTargetTest {

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

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                // Plafond d'étapes par défaut (30) sauf mention contraire du test (SF-28-19).
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null));

        // Porte de validation (SF-38-08) : ces tests-ci portent sur le routage, pas sur la
        // validation — on autorise donc systématiquement, en relayant quand même la demande à
        // l'écran comme le fait la vraie porte.
        when(confirmationGate.await(any(), any(), anyString(), any())).thenAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return new fr.claudegateway.runner.exec.RunnerConfirmationGate.Outcome(
                    fr.claudegateway.runner.exec.RunnerConfirmationGate.Decision.ALLOW, null);
        });

        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        // Quota lu pour dériver le plafond de consommation du message (F-39 / SF-39-15).
        org.mockito.Mockito.lenient().when(quotaService.currentUsage(userId)).thenReturn(
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
        // Consigne système : par défaut le runner ne renvoie rien d'exploitable.
        when(runnerToolGateway.listFiles(eq(workspaceId), anyString())).thenReturn(ok(""));
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), anyString()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    private Workspace stubWorkspace(WorkspaceSource source, WorkspaceExecutionTarget target) {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(source);
        workspace.setExecutionTarget(target);
        if (source == WorkspaceSource.GIT) {
            workspace.setGitOwner("acme");
            workspace.setGitRepo("app");
            workspace.setGitBranch("main");
        }
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        return workspace;
    }

    @Test
    void readFileGoesToTheRunnerAndNeverToObjectStorage() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), eq("src/a.ts")))
                .thenReturn(ok("const x = 1;"));
        agentProvider.enqueueToolCall("read_file", "path", "src/a.ts");
        agentProvider.enqueueFinal("Lu.");

        AtelierChatResult result = service.chat(userId, workspaceId, "lis src/a.ts");

        assertThat(result.actions()).extracting(a -> a.type() + ":" + a.path()).contains("read:src/a.ts");
        verify(runnerToolGateway).readFile(eq(workspaceId), anyString(), eq("src/a.ts"));
        verify(workspaceService, never()).readFile(any(), any(), any());
        // SF-39-06 : la lecture est numérotée — sans numéros, l'agent ne peut ni dire où il a vu
        // quelque chose, ni demander la suite d'un fichier.
        assertThat(toolResultText()).isEqualTo("     1→const x = 1;\n");
    }

    @Test
    void aTruncatedContentIsAnnouncedToTheModel() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), eq("gros.txt")))
                .thenReturn(new RunnerCallResult(true, "début", true, null, 5L, null, null, null, "", false));
        agentProvider.enqueueToolCall("read_file", "path", "gros.txt");
        agentProvider.enqueueFinal("Lu.");

        service.chat(userId, workspaceId, "lis gros.txt");

        assertThat(toolResultText()).isEqualTo("     1→début\n\n… (contenu tronqué)");
    }

    @Test
    void writeFileKeepsTheHistoricalWordingAndIgnoresTheRunnerContent() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.writeFile(eq(workspaceId), anyString(), eq("a.txt"), eq("hop")))
                .thenReturn(ok("peu importe"));
        agentProvider.enqueueToolCall("write_file", "path", "a.txt", "content", "hop");
        agentProvider.enqueueFinal("Écrit.");

        AtelierChatResult result = service.chat(userId, workspaceId, "écris a.txt");

        assertThat(result.actions()).extracting(a -> a.type() + ":" + a.path()).contains("write:a.txt");
        assertThat(toolResultText()).isEqualTo("Fichier écrit : a.txt");
        verify(workspaceService, never()).writeFile(any(), any(), any(), any());
    }

    @Test
    void searchIsASingleRunnerCallNotOneReadPerFile() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.searchFiles(eq(workspaceId), anyString(), eq("TODO")))
                .thenReturn(ok("src/a.ts:3: // TODO"));
        agentProvider.enqueueToolCall("search_files", "query", "TODO");
        agentProvider.enqueueFinal("Trouvé.");

        service.chat(userId, workspaceId, "cherche TODO");

        verify(runnerToolGateway, times(1)).searchFiles(eq(workspaceId), anyString(), eq("TODO"));
        verify(workspaceService, never()).tree(any(), any());
        assertThat(toolResultText()).isEqualTo("src/a.ts:3: // TODO");
    }

    @Test
    void aRunnerErrorIsReturnedToTheModelAsAToolError() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), eq("a.ts")))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));
        agentProvider.enqueueToolCall("read_file", "path", "a.ts");
        agentProvider.enqueueFinal("Tant pis.");

        service.chat(userId, workspaceId, "lis a.ts");

        AgentContentBlock.ToolResult block = lastToolResult();
        assertThat(block.isError()).isTrue();
        assertThat(block.content()).contains("Aucun runner");
    }

    @Test
    void theSystemPromptIsReadThroughTheRunner() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.listFiles(eq(workspaceId), anyString()))
                .thenReturn(ok(".claude/skills/deploy.md\nsrc/a.ts"));
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), eq("CLAUDE.md")))
                .thenReturn(ok("# Conventions maison"));
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), eq(".claude/skills/deploy.md")))
                .thenReturn(ok("Skill de déploiement"));
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "salut");

        assertThat(agentProvider.lastRequest.system()).contains("# Conventions maison");
        assertThat(agentProvider.lastRequest.system()).contains("Skill de déploiement");
        verify(workspaceService, never()).tree(any(), any());
    }

    @Test
    void aGitProjectIsNoLongerRefusedWhenItRunsOnTheMachine() {
        // Le dépôt est cloné sur la machine de l'utilisateur : le garde-fou « mode Terminal
        // obligatoire » (F-31) serait ici un faux positif.
        stubWorkspace(WorkspaceSource.GIT, WorkspaceExecutionTarget.RUNNER);
        agentProvider.enqueueFinal("Bonjour.");

        AtelierChatResult result = service.chat(userId, workspaceId, "salut");

        assertThat(result.reply()).isEqualTo("Bonjour.");
    }

    @Test
    void aGitProjectInSandboxTargetIsStillRefused() {
        // Non-régression F-31 / SF-31-03 : sans runner, le stockage objet est bien vide.
        stubWorkspace(WorkspaceSource.GIT, WorkspaceExecutionTarget.SANDBOX);
        agentProvider.enqueueFinal("Bonjour.");

        assertThatThrownBy(() -> service.chat(userId, workspaceId, "salut"))
                .isInstanceOf(fr.claudegateway.atelier.git.GitWorkspaceModeException.class);
    }

    @Test
    void aToolCallWithoutProviderIdStillCorrelates() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.listFiles(eq(workspaceId), anyString())).thenReturn(ok("a.ts"));
        agentProvider.enqueueToolCallWithoutId("list_files");
        agentProvider.enqueueFinal("Fini.");

        service.chat(userId, workspaceId, "liste");

        // Le bloc tool_result doit référencer un id non vide, sinon le fournisseur rejette le tour.
        assertThat(lastToolResult().toolUseId()).isNotBlank();
    }

    /** Texte du dernier {@code tool_result} transmis au modèle. */
    private String toolResultText() {
        return lastToolResult().content();
    }

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

    // ------------------------------------------------------------ outil bash (SF-38-07)

    @Test
    void theToolBeltFollowsWhatTheTargetCanActuallyDo() {
        // SF-39-05 (D4) : là où bash existe, `ls`/`grep` remplacent list_files et search_files —
        // deux définitions de moins dans le préfixe caché, payées à chaque itération. En SANDBOX,
        // où il n'y a pas de bash, les retirer priverait le modèle de tout moyen d'explorer.
        Workspace sandbox = new Workspace();
        sandbox.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        Workspace runner = new Workspace();
        runner.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);

        assertThat(service.buildTools(sandbox)).extracting(fr.claudegateway.agent.AgentTool::name)
                .containsExactly("list_files", "read_file", "write_file", "edit_file", "search_files");
        assertThat(service.buildTools(runner)).extracting(fr.claudegateway.agent.AgentTool::name)
                .containsExactly("read_file", "write_file", "edit_file", "bash");
    }

    @Test
    void anUndeclaredListFilesIsStillRelayedRatherThanRefused() {
        // SF-39-05 (D2) : on retire la DÉCLARATION, qui coûte des tokens — pas la capacité. Un
        // modèle qui appelle quand même l'outil obtient une réponse utile, pas « outil inconnu ».
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.listFiles(eq(workspaceId), anyString())).thenReturn(ok("a.ts\nb.ts"));
        agentProvider.enqueueToolCall("list_files");
        agentProvider.enqueueFinal("Listé.");

        service.chat(userId, workspaceId, "liste les fichiers");

        assertThat(toolResultText()).isEqualTo("a.ts\nb.ts");
        assertThat(lastToolResult().isError()).isFalse();
    }

    @Test
    void editFileReadsThenWritesOnTheMachineWithoutTouchingTheProtocol() {
        // SF-39-06 (D1) : l'édition ciblée se compose des primitives que le runner expose déjà —
        // un runner installé n'a rien à mettre à jour.
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), eq("a.ts")))
                .thenReturn(ok("const a = 1;"));
        when(runnerToolGateway.writeFile(eq(workspaceId), anyString(), eq("a.ts"), eq("const a = 2;")))
                .thenReturn(ok(""));
        agentProvider.enqueueToolCall("edit_file", "path", "a.ts", "old_string", "1", "new_string", "2");
        agentProvider.enqueueFinal("Modifié.");

        AtelierChatResult result = service.chat(userId, workspaceId, "passe a à 2");

        verify(runnerToolGateway).writeFile(eq(workspaceId), anyString(), eq("a.ts"), eq("const a = 2;"));
        assertThat(toolResultText()).isEqualTo("Fichier modifié : a.ts (1 remplacement)");
        // L'écran voit une écriture : c'est ce qui rafraîchit le fichier ouvert (D4).
        assertThat(result.actions()).extracting(a -> a.type() + ":" + a.path()).contains("write:a.ts");
    }

    @Test
    void editFileRefusesToWriteBackATruncatedRead() {
        // SF-39-06 (D2) : appliquer un remplacement sur un fragment puis le réécrire détruirait la
        // fin du fichier, en silence. C'est le seul refus d'une opération que le modèle croit possible.
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), eq("gros.ts")))
                .thenReturn(new RunnerCallResult(true, "const a = 1;", true, null, 5L, null, null, null, "", false));
        agentProvider.enqueueToolCall("edit_file", "path", "gros.ts", "old_string", "1", "new_string", "2");
        agentProvider.enqueueFinal("Refusé.");

        service.chat(userId, workspaceId, "passe a à 2");

        verify(runnerToolGateway, never()).writeFile(any(), anyString(), anyString(), anyString());
        assertThat(lastToolResult().isError()).isTrue();
        assertThat(toolResultText()).contains("tronquée");
    }

    @Test
    void editFileIsDeclaredOnTheMachineAndJournaledWithItsPath() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        Workspace runner = new Workspace();
        runner.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), eq("a.ts"))).thenReturn(ok("x"));
        when(runnerToolGateway.writeFile(eq(workspaceId), anyString(), eq("a.ts"), eq("y"))).thenReturn(ok(""));
        agentProvider.enqueueToolCall("edit_file", "path", "a.ts", "old_string", "x", "new_string", "y");
        agentProvider.enqueueFinal("Modifié.");

        service.chat(userId, workspaceId, "remplace x par y");

        assertThat(service.buildTools(runner)).extracting(fr.claudegateway.agent.AgentTool::name)
                .containsExactly("read_file", "write_file", "edit_file", "bash");
        verify(runnerAuditService).recordCall(eq(userId), eq(workspaceId), anyString(), eq("edit_file"),
                eq("a.ts"), any());
    }

    @Test
    void bashAssemblesStreamedOutputAndExitCodeForTheModel() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.bash(eq(workspaceId), anyString(), eq("npm test"), any(), anyLong(), any()))
                .thenReturn(bashOk("ok 1\nok 2\n", 0, false));
        agentProvider.enqueueToolCall("bash", "command", "npm test");
        agentProvider.enqueueFinal("Tests passés.");

        service.chat(userId, workspaceId, "lance les tests");

        assertThat(toolResultText()).isEqualTo("$ npm test\nok 1\nok 2\n[code de sortie: 0]");
        assertThat(lastToolResult().isError()).isFalse();
    }

    @Test
    void aNonZeroExitCodeIsStillASuccessfulCall() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.bash(eq(workspaceId), anyString(), eq("false"), any(), anyLong(), any()))
                .thenReturn(bashOk("", 1, false));
        agentProvider.enqueueToolCall("bash", "command", "false");
        agentProvider.enqueueFinal("Échec constaté.");

        service.chat(userId, workspaceId, "lance");

        // La commande a tourné : le modèle doit lire son code de sortie, pas un message d'erreur.
        assertThat(lastToolResult().isError()).isFalse();
        assertThat(toolResultText()).isEqualTo("$ false\n[code de sortie: 1]");
    }

    @Test
    void aTruncatedOutputIsMarkedForTheModel() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.bash(eq(workspaceId), anyString(), eq("cat gros.log"), any(), anyLong(), any()))
                .thenReturn(bashOk("début…", 0, true));
        agentProvider.enqueueToolCall("bash", "command", "cat gros.log");
        agentProvider.enqueueFinal("Vu.");

        service.chat(userId, workspaceId, "affiche");

        assertThat(toolResultText()).contains("… (sortie tronquée)").endsWith("[code de sortie: 0]");
    }

    @Test
    void aFailedBashCallIsReturnedAsAnErrorToolResult() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.bash(eq(workspaceId), anyString(), anyString(), any(), anyLong(), any()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.UNSUPPORTED_TOOL,
                        "L'exécution de commandes n'est pas activée sur ce runner."));
        agentProvider.enqueueToolCall("bash", "command", "rm -rf /");
        agentProvider.enqueueFinal("Refusé.");

        service.chat(userId, workspaceId, "lance");

        assertThat(lastToolResult().isError()).isTrue();
        assertThat(toolResultText()).contains("pas activée sur ce runner");
    }

    @Test
    void theBashStepCarriesTheCommandTruncatedForTheScreen() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        String longCommand = "echo " + "x".repeat(500);
        when(runnerToolGateway.bash(eq(workspaceId), anyString(), eq(longCommand), any(), anyLong(), any()))
                .thenReturn(bashOk("", 0, false));
        agentProvider.enqueueToolCall("bash", "command", longCommand);
        agentProvider.enqueueFinal("Fait.");
        RecordingListener listener = new RecordingListener();

        service.chatStreaming(userId, workspaceId, "lance", listener);

        assertThat(listener.steps).hasSize(1);
        assertThat(listener.steps.get(0).type()).isEqualTo("bash");
        assertThat(listener.steps.get(0).path()).hasSize(200).isEqualTo(longCommand.substring(0, 200));
    }

    @Test
    void commandOutputIsRelayedToTheSessionWhileItRuns() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        // Le relais est le consommateur passé à la passerelle : on le déclenche depuis le stub, comme
        // le ferait une trame tool_stream arrivant pendant l'exécution.
        when(runnerToolGateway.bash(eq(workspaceId), anyString(), anyString(), any(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Consumer<String> relay = invocation.getArgument(5);
                    relay.accept("ligne 1\n");
                    relay.accept("ligne 2\n");
                    return bashOk("ligne 1\nligne 2\n", 0, false);
                });
        agentProvider.enqueueToolCall("bash", "command", "make");
        agentProvider.enqueueFinal("Fini.");
        RecordingListener listener = new RecordingListener();

        service.chatStreaming(userId, workspaceId, "lance", listener);

        assertThat(listener.outputs).containsExactly("ligne 1\n", "ligne 2\n");
    }

    @Test
    void bashTimeoutIsCappedByTheRemainingTurnBudget() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.bash(eq(workspaceId), anyString(), anyString(), any(), anyLong(), any()))
                .thenReturn(bashOk("", 0, false));
        agentProvider.enqueueToolCall("bash", "command", "sleep 1");
        agentProvider.enqueueFinal("Fini.");

        service.chat(userId, workspaceId, "lance");

        org.mockito.ArgumentCaptor<Long> timeout = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(runnerToolGateway).bash(eq(workspaceId), anyString(), anyString(), any(),
                timeout.capture(), any());
        // Le délai proposé ne dépasse jamais ce qu'il reste du tour (la passerelle le clampe ensuite
        // à 120 000 ms) : une commande ne peut pas survivre au tour qui l'a lancée.
        assertThat(timeout.getValue()).isPositive()
                .isLessThanOrEqualTo(AtelierChatService.TURN_BUDGET_MS);
    }

    @Test
    void anInterruptionStopsTheLoopAndCancelsTheRunnerCall() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.bash(eq(workspaceId), anyString(), anyString(), any(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    // Interruption demandée pendant que la commande tourne.
                    service.interruptChat(userId, workspaceId);
                    return RunnerCallResult.backendError("cancelled", "Appel interrompu.");
                });
        agentProvider.enqueueToolCall("bash", "command", "sleep 300");
        agentProvider.enqueueToolCall("bash", "command", "echo jamais");
        agentProvider.enqueueFinal("Ne doit pas arriver.");

        AtelierChatResult result = service.chat(userId, workspaceId, "lance");

        assertThat(result.reply()).isEqualTo(AtelierChatService.INTERRUPTED_REPLY);
        verify(runnerCallDispatcher).cancelWorkspace(workspaceId, "user_interrupt");
        // La seconde commande n'a jamais été lancée : la boucle s'est arrêtée à la frontière sûre.
        verify(runnerToolGateway, times(1))
                .bash(eq(workspaceId), anyString(), anyString(), any(), anyLong(), any());
    }

    @Test
    void anInterruptionOnSomeoneElsesWorkspaceIsRefused() {
        UUID intruder = UUID.randomUUID();
        when(workspaceService.requireOwned(intruder, workspaceId))
                .thenThrow(new WorkspaceNotFoundException("Projet introuvable"));

        assertThatThrownBy(() -> service.interruptChat(intruder, workspaceId))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verify(runnerCallDispatcher, never()).cancelWorkspace(any(), anyString());
    }

    private static RunnerCallResult bashOk(String streamed, int exitCode, boolean truncated) {
        return new RunnerCallResult(true, "", false, exitCode, 12L, null, null, null, streamed, truncated);
    }

    /** Listener de test : mémorise étapes et fragments de sortie relayés. */
    private static final class RecordingListener implements AtelierProgressListener {
        private final List<AtelierProgressListener.AtelierStepEvent> steps = new java.util.ArrayList<>();
        private final List<String> outputs = new java.util.ArrayList<>();

        @Override
        public void onAction(AtelierProgressListener.AtelierStepEvent step) {
            steps.add(step);
        }

        @Override
        public void onText(String text) {
            // Non utilisé ici.
        }

        @Override
        public void onOutput(String chunk) {
            outputs.add(chunk);
        }
    }
}
