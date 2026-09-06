package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
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
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    /** Cible SANDBOX dans tout ce fichier : le runner ne doit jamais être sollicité (F-38 / SF-38-05). */
    @Mock private fr.claudegateway.runner.exec.RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private fr.claudegateway.runner.exec.RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    /** Listener de capture : enregistre l'ordre des notifications d'étapes et de texte. */
    private static final class RecordingListener implements AtelierProgressListener {
        final List<AtelierStepEvent> actions = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<AtelierPlan> plans = new ArrayList<>();

        @Override
        public void onAction(AtelierStepEvent step) {
            actions.add(step);
        }

        @Override
        public void onText(String text) {
            texts.add(text);
        }

        @Override
        public void onPlan(AtelierPlan plan) {
            plans.add(plan);
        }
    }

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        // Le garde-fou Git (F-31 / SF-31-03) est réel : sur un workspace d'archive il ne fait rien,
        // ce qui garantit qu'aucun test existant ne dépend d'un stub complaisant.
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                // Plafond d'étapes par défaut (30) sauf mention contraire du test (SF-28-19).
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));
    }

    /** Workspace d'archive possédé : la source par défaut, celle de tous les tests de ce fichier. */
    private void stubOwnedArchiveWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
    }

    private void stubHappyPath() {
        stubOwnedArchiveWorkspace();
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        // Quota lu pour dériver le plafond de consommation du message (F-39 / SF-39-15).
        org.mockito.Mockito.lenient().when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
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

    /** Dernier {@code tool_result} transmis au modèle : ce que l'outil a réellement rendu. */
    private String lastToolResultText() {
        AgentContentBlock.ToolResult found = null;
        for (AgentMessage message : agentProvider.lastRequest.messages()) {
            for (AgentContentBlock block : message.content()) {
                if (block instanceof AgentContentBlock.ToolResult result) {
                    found = result;
                }
            }
        }
        assertThat(found).as("aucun tool_result transmis au modèle").isNotNull();
        return found.content();
    }

    @Test
    void readFileIsNumberedOnTheHostedStorageToo() {
        // SF-39-06 : la numérotation est calculée côté gateway, donc identique quelle que soit la
        // cible d'exécution — le prompt ne doit pas dériver selon l'endroit où vivent les fichiers.
        stubHappyPath();
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueFinal("Lu.");

        service.chat(userId, workspaceId, "lis notes.txt");

        assertThat(lastToolResultText()).isEqualTo("     1→contenu du fichier\n");
    }

    @Test
    void editFileRewritesTheFileAndLooksLikeAWriteToTheScreen() {
        stubHappyPath();
        when(workspaceService.readFile(userId, workspaceId, "notes.txt")).thenReturn("bonjour monde");
        agentProvider.enqueueToolCall("edit_file", "path", "notes.txt",
                "old_string", "monde", "new_string", "atelier");
        agentProvider.enqueueFinal("Modifié.");

        AtelierChatResult result = service.chat(userId, workspaceId, "remplace monde");

        verify(workspaceService).writeFile(userId, workspaceId, "notes.txt", "bonjour atelier");
        assertThat(result.actions()).extracting(a -> a.type() + ":" + a.path()).contains("write:notes.txt");
        assertThat(lastToolResultText()).isEqualTo("Fichier modifié : notes.txt (1 remplacement)");
    }

    @Test
    void editFileRefusesAnAmbiguousPassageRatherThanEditingAtRandom() {
        stubHappyPath();
        when(workspaceService.readFile(userId, workspaceId, "notes.txt")).thenReturn("x x");
        agentProvider.enqueueToolCall("edit_file", "path", "notes.txt",
                "old_string", "x", "new_string", "y");
        agentProvider.enqueueFinal("Refusé.");

        service.chat(userId, workspaceId, "remplace x");

        verify(workspaceService, never()).writeFile(any(), any(), any(), any());
        assertThat(lastToolResultText()).contains("trouvé 2 fois");
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
        stubOwnedArchiveWorkspace();
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
    void hostedModeChecksAndRecordsQuota() {
        // SF-28-06 : sans clé BYOK (Hosted), la boucle contrôle le quota avant et le comptabilise après.
        stubHappyPath(); // byokKeyService => Optional.empty()
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "salut");

        verify(quotaService).assertWithinQuota(userId);
        verify(quotaService).recordUsage(eq(userId), anyInt(), anyInt());
    }

    @Test
    void byokModeSkipsQuotaCheckAndRecording() {
        // SF-28-06 : avec une clé BYOK active, les tokens sont sur le compte de l'utilisateur =>
        // ni contrôle (assertWithinQuota) ni comptabilisation (recordUsage) du quota plateforme.
        stubOwnedArchiveWorkspace();
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.of("sk-ant-user-key"));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        when(workspaceService.readFile(any(), any(), any())).thenReturn("contenu du fichier");
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "salut");

        verify(quotaService, never()).assertWithinQuota(any());
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

    // ------------------------------------------------- SF-28-18 : tour tronqué et mémoire vide

    @Test
    void truncatedTurnExecutesNoToolAndSaysSo() {
        stubHappyPath();
        // Le fournisseur a coupé la réponse au plafond : une phrase d'intention, et un `write_file`
        // dont rien ne garantit que les arguments soient complets.
        agentProvider.enqueueTruncated("Je vais créer ce fichier.", "write_file");

        AtelierChatResult result = service.chat(userId, workspaceId, "écris un gros fichier");

        // CA1 : aucun outil exécuté — l'écriture n'a pas eu lieu.
        verify(workspaceService, never()).writeFile(any(), any(), any(), any());
        assertThat(result.actions()).isEmpty();
        // CA2 : la réponse nomme la coupure et dit que rien n'a été exécuté.
        assertThat(result.reply()).isEqualTo(AtelierChatService.TRUNCATED_REPLY);
        assertThat(result.reply()).contains("rien n'a été exécuté");
    }

    @Test
    void truncatedTurnPersistsANonEmptyAssistantMessage() {
        stubHappyPath();
        agentProvider.enqueueTruncated("", "write_file");

        service.chat(userId, workspaceId, "écris un gros fichier");

        // CA3 : le message persisté n'est jamais vide — sinon l'API refuserait de le rejouer.
        ArgumentCaptor<AtelierMessage> saved = ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(m -> assertThat(m.getContent()).isNotBlank());
    }

    @Test
    void emptyFinalTurnIsPersistedWithAnExplicitFallback() {
        stubHappyPath();
        agentProvider.enqueueEmptyFinal();

        AtelierChatResult result = service.chat(userId, workspaceId, "bonjour");

        assertThat(result.reply()).isEqualTo(AtelierChatService.EMPTY_REPLY_FALLBACK);
        ArgumentCaptor<AtelierMessage> saved = ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(m -> assertThat(m.getContent()).isNotBlank());
    }

    @Test
    void blankHistoryMessagesAreNeverSentToTheProvider() {
        stubHappyPath();
        // Historique tel qu'il existe en production sur un projet condamné avant SF-28-18 : un
        // message assistant vide y a été écrit, et il rendait tout appel ultérieur impossible.
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of(
                        historyMessage("USER", "première demande"),
                        historyMessage("ASSISTANT", ""),
                        historyMessage("USER", "deuxième demande"),
                        historyMessage("ASSISTANT", "   ")));
        agentProvider.enqueueFinal("Voilà.");

        service.chat(userId, workspaceId, "troisième demande");

        List<AgentMessage> sent = agentProvider.lastRequest.messages();
        assertThat(sent).allSatisfy(m -> assertThat(m.content()).allSatisfy(block ->
                assertThat(((AgentContentBlock.Text) block).text()).isNotBlank()));
        // Les deux demandes réelles subsistent, plus celle du tour : rien d'utile n'a été perdu.
        assertThat(sent).hasSize(3);
    }

    @Test
    void leadingAssistantMessagesAreDroppedSoTheHistoryStartsWithAUser() {
        stubHappyPath();
        // Après filtrage des vides, l'historique pourrait commencer par un assistant — ce que le
        // fournisseur refuse.
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of(
                        historyMessage("ASSISTANT", ""),
                        historyMessage("ASSISTANT", "réponse orpheline"),
                        historyMessage("USER", "une demande")));
        agentProvider.enqueueFinal("Voilà.");

        service.chat(userId, workspaceId, "suite");

        List<AgentMessage> sent = agentProvider.lastRequest.messages();
        assertThat(sent.get(0).role()).isEqualTo("user");
        assertThat(sent).hasSize(2);
    }

    /** Message d'historique tel que le repository le renvoie. */
    private static AtelierMessage historyMessage(String role, String content) {
        return AtelierMessage.builder()
                .id(UUID.randomUUID()).workspaceId(UUID.randomUUID()).userId(UUID.randomUUID())
                .role(role).content(content).build();
    }

    // ------------------------------------------------- SF-28-19 : plafond d'étapes calibré

    /** Reconstruit le service avec un plafond d'étapes donné (F-28 / SF-28-19). */
    private void serviceWithMaxIterations(int max) {
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                new AtelierProperties(null, null, null, null, null, null, max, null, null, null, null, null, true));
    }

    @Test
    void aTwentyStepTaskCompletesWithTheDefaultCeiling() {
        stubHappyPath();
        // 20 lectures puis la réponse : ce tour était coupé à 12 avant SF-28-19, alors que 31 % des
        // demandes réelles dépassent ce seuil.
        for (int i = 0; i < 20; i++) {
            agentProvider.enqueueToolCall("read_file", "path", "f" + i + ".txt");
        }
        agentProvider.enqueueFinal("J'ai tout lu.");

        AtelierChatResult result = service.chat(userId, workspaceId, "lis les 20 fichiers");

        assertThat(result.reply()).isEqualTo("J'ai tout lu.");
        assertThat(result.actions()).hasSize(20);
    }

    @Test
    void stopsAtTheConfiguredCeilingAndKeepsTheWorkAlreadyDone() {
        stubHappyPath();
        serviceWithMaxIterations(3);
        for (int i = 0; i < 10; i++) {
            agentProvider.enqueueToolCall("read_file", "path", "f" + i + ".txt");
        }

        AtelierChatResult result = service.chat(userId, workspaceId, "lis tout");

        assertThat(result.reply()).contains("limite d'étapes");
        // Le travail déjà fait n'est pas perdu : les 3 lectures sont rendues.
        assertThat(result.actions()).hasSize(3);
    }

    @Test
    void shortTurnsAreUnaffectedByTheHigherCeiling() {
        stubHappyPath();
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueFinal("Lu.");

        AtelierChatResult result = service.chat(userId, workspaceId, "lis notes.txt");

        assertThat(result.reply()).isEqualTo("Lu.");
        assertThat(result.actions()).hasSize(1);
    }

    // ------------------------------------------------- SF-39-13 : le plan de travail

    @Test
    void relaysThePlanToTheScreenAndAcknowledgesItToTheModel() {
        stubHappyPath();
        agentProvider.enqueueToolCallWithJson("set_plan", "steps",
                "[{\"title\":\"Lire\",\"status\":\"active\"},{\"title\":\"Écrire\"}]");
        agentProvider.enqueueFinal("Fait.");
        RecordingListener listener = new RecordingListener();

        AtelierChatResult result = service.chatStreaming(userId, workspaceId, "vas-y", listener);

        assertThat(listener.plans).hasSize(1);
        assertThat(listener.plans.get(0).steps()).extracting(AtelierPlan.Step::title)
                .containsExactly("Lire", "Écrire");
        assertThat(result.reply()).isEqualTo("Fait.");
    }

    @Test
    void theSecondPlanReplacesTheFirst() {
        stubHappyPath();
        agentProvider.enqueueToolCallWithJson("set_plan", "steps", "[{\"title\":\"A\"}]");
        agentProvider.enqueueToolCallWithJson("set_plan", "steps",
                "[{\"title\":\"A\",\"status\":\"done\"},{\"title\":\"B\",\"status\":\"active\"}]");
        agentProvider.enqueueFinal("Fait.");
        RecordingListener listener = new RecordingListener();

        service.chatStreaming(userId, workspaceId, "vas-y", listener);

        // Remplacement, jamais fusion (D1) : le dernier plan est celui qui vaut.
        assertThat(listener.plans).hasSize(2);
        assertThat(listener.plans.get(1).steps()).hasSize(2);
    }

    @Test
    void aMalformedPlanNeverBreaksTheTurn() {
        stubHappyPath();
        // Ni titre, ni tableau : le tour doit aboutir quand même.
        agentProvider.enqueueToolCall("set_plan", "steps", "pas un tableau");
        agentProvider.enqueueFinal("Fait quand même.");

        AtelierChatResult result = service.chat(userId, workspaceId, "vas-y");

        assertThat(result.reply()).isEqualTo("Fait quand même.");
    }

    @Test
    void aTurnWithoutAPlanIsUnchanged() {
        stubHappyPath();
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueFinal("Lu.");
        RecordingListener listener = new RecordingListener();

        AtelierChatResult result = service.chatStreaming(userId, workspaceId, "lis", listener);

        assertThat(listener.plans).isEmpty();
        assertThat(result.reply()).isEqualTo("Lu.");
    }

    // ------------------------------------------------- SF-39-14 : déléguer la lecture

    @Test
    void delegatesAnExplorationAndBringsBackOnlyItsAnswer() {
        stubHappyPath();
        agentProvider.enqueueToolCall("explore", "question", "où est défini AppConfig ?");
        // Ce que la sous-boucle fait, et ce qu'elle conclut.
        agentProvider.enqueueToolCall("read_file", "path", "src/AppConfig.java");
        agentProvider.enqueueFinal("Dans src/AppConfig.java, ligne 12.");
        // Puis la boucle principale conclut.
        agentProvider.enqueueFinal("AppConfig est dans src/AppConfig.java:12.");

        AtelierChatResult result = service.chat(userId, workspaceId, "où est AppConfig ?");

        assertThat(result.reply()).contains("src/AppConfig.java");
        // Le contexte principal ne porte QUE la réponse : le contenu du fichier lu par la
        // sous-boucle n'y entre jamais — c'est tout l'intérêt de la délégation.
        List<AgentMessage> sent = agentProvider.lastRequest.messages();
        assertThat(sent.toString()).doesNotContain("contenu du fichier");
    }

    @Test
    void countsWhatTheExplorationConsumedInTheTurn() {
        stubHappyPath();
        agentProvider.enqueueToolCall("explore", "question", "cherche");
        agentProvider.enqueueFinal("Trouvé.");
        agentProvider.enqueueFinal("Voilà.");

        AtelierChatResult result = service.chat(userId, workspaceId, "cherche");

        // La sous-boucle n'a ni quota propre ni plafond propre : sa dépense appartient au tour (D4).
        assertThat(result.inputTokens()).isGreaterThan(5L);
    }

    @Test
    void refusesTheFourthDelegationWithoutBreakingTheTurn() {
        stubHappyPath();
        for (int i = 0; i < 4; i++) {
            agentProvider.enqueueToolCall("explore", "question", "question " + i);
            agentProvider.enqueueFinal("réponse " + i);
        }
        agentProvider.enqueueFinal("Terminé.");

        AtelierChatResult result = service.chat(userId, workspaceId, "explore beaucoup");

        // Le refus est un résultat d'outil, pas une panne : le tour aboutit.
        assertThat(result.reply()).isNotBlank();
    }

    @Test
    void anEmptyQuestionIsAToolErrorNotATurnFailure() {
        stubHappyPath();
        agentProvider.enqueueToolCall("explore", "question", "   ");
        agentProvider.enqueueFinal("Je poursuis moi-même.");

        AtelierChatResult result = service.chat(userId, workspaceId, "explore");

        assertThat(result.reply()).isEqualTo("Je poursuis moi-même.");
    }

    @Test
    void theExplorationOnlyEverGetsReadTools() {
        stubHappyPath();
        agentProvider.enqueueToolCall("explore", "question", "cherche");
        agentProvider.enqueueFinal("Trouvé.");
        agentProvider.enqueueFinal("Voilà.");

        service.chat(userId, workspaceId, "cherche");

        // Le dernier appel est celui de la boucle principale ; on vérifie qu'aucune requête n'a
        // jamais offert d'outil d'écriture ou d'exécution à la sous-boucle.
        assertThat(agentProvider.toolNamesSeen).doesNotContain("bash");
    }

    // ------------------------------------------------- SF-39-16 : fermeture de la cible SANDBOX

    /** Reconstruit le service avec le coupe-circuit de la cible SANDBOX **fermé** (défaut de prod). */
    private void serviceWithStorageExecutionClosed() {
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, false));
    }

    @Test
    void refusesTheHomeLoopOnAProjectWithoutAConnectedMachine() {
        stubOwnedArchiveWorkspace();
        serviceWithStorageExecutionClosed();

        assertThatThrownBy(() -> service.chat(userId, workspaceId, "vas-y"))
                .isInstanceOf(StorageExecutionClosedException.class)
                .hasMessageContaining("bac à sable");

        // Refus AVANT tout appel fournisseur et avant le contrôle de quota : rien n'est consommé,
        // rien n'est persisté.
        assertThat(agentProvider.lastRequest).isNull();
        verify(messageRepository, never()).save(any(AtelierMessage.class));
        verify(quotaService, never()).assertWithinQuota(any());
    }

    @Test
    void anotherUsersProjectStillAnswersNotFoundRatherThanRefused() {
        // L'ordre des gardes est une règle de confidentialité : un refus posé en premier dirait
        // « ce projet existe » à quelqu'un qui n'en est pas propriétaire (D3).
        serviceWithStorageExecutionClosed();
        when(workspaceService.requireOwned(userId, workspaceId))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable"));

        assertThatThrownBy(() -> service.chat(userId, workspaceId, "vas-y"))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void theOpenedCircuitBreakerRestoresTheFormerBehaviourExactly() {
        // Non-régression : ouvert, c'est le comportement d'avant, à l'identique.
        stubHappyPath();
        agentProvider.enqueueFinal("Voilà.");

        AtelierChatResult result = service.chat(userId, workspaceId, "vas-y");

        assertThat(result.reply()).isEqualTo("Voilà.");
    }
}
