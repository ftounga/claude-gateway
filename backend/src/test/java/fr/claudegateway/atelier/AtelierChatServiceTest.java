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
import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierStepEvent;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierSteer;
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
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;

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
                runnerHostService,
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

    // ------------------------------------------- F-116 / SF-116-01 : le texte défile mot à mot

    @Test
    void relaysTheAnswerAsTextDeltasWhenStreamingIsOn() {
        // Le flux est actif par défaut : le texte de la réponse défile mot à mot via onText, au lieu
        // d'être découvert d'un bloc à la fin du tour.
        stubHappyPath();
        agentProvider.emitTextDeltas = true;
        agentProvider.enqueueFinal("bonjour le monde");
        RecordingListener listener = new RecordingListener();

        AtelierChatResult result = service.chatStreaming(userId, workspaceId, "salut", listener);

        assertThat(listener.texts).containsExactly("bonjour ", "le ", "monde");
        // La réponse persistée reste le texte complet : seul le MOMENT d'affichage change.
        assertThat(result.reply()).isEqualTo("bonjour le monde");
    }

    @Test
    void doesNotAlsoRelayTheFullCommentWhenItWasAlreadyStreamed() {
        // Le commentaire d'un tour à outils défile via les deltas ; le relayer une seconde fois entier
        // le doublerait à l'écran. Garde-fou anti-duplication (SF-116-01, D4).
        stubHappyPath();
        agentProvider.emitTextDeltas = true;
        agentProvider.enqueueToolCallWithReasoning("read_file", "sig-1", "path", "notes.txt");
        agentProvider.enqueueFinal("fini");
        RecordingListener listener = new RecordingListener();

        service.chatStreaming(userId, workspaceId, "lis notes.txt", listener);

        // Le commentaire « je regarde » a défilé en deltas — jamais relayé entier en plus.
        assertThat(listener.texts).containsExactly("je ", "regarde", "fini");
        assertThat(listener.texts).doesNotContain("je regarde");
    }

    @Test
    void relaysTheWholeCommentOnceWhenStreamingIsOff() {
        // Coupe-circuit : flux désactivé => appel complet, commentaire relayé entier en fin de tour
        // (comportement historique).
        AtelierChatService noStream = new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null,
                        null, true, false));
        stubHappyPath();
        agentProvider.emitTextDeltas = true; // même si le stub sait streamer, le service ne le demande pas
        agentProvider.enqueueToolCallWithReasoning("read_file", "sig-1", "path", "notes.txt");
        agentProvider.enqueueFinal("fini");
        RecordingListener listener = new RecordingListener();

        noStream.chatStreaming(userId, workspaceId, "lis notes.txt", listener);

        // Aucun delta : le commentaire intermédiaire est relayé entier, comme avant F-116.
        assertThat(listener.texts).containsExactly("je regarde");
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
        // SF-119-05 : l'édition d'un fichier non lu dans ce fil est suivie d'un rappel léger de
        // lecture-avant-édition — le message d'édition lui-même reste en tête, inchangé.
        assertThat(lastToolResultText()).startsWith("Fichier modifié : notes.txt (1 remplacement)");
    }

    // -------------------------------------------------- F-121 / SF-121-06 : MultiEdit (hébergé)

    @Test
    void multiEditAppliesEveryEditAtomicallyInOneWrite() {
        // F-121 / SF-121-06 : plusieurs remplacements séquentiels sur l'arbre hébergé, une seule
        // écriture avec le contenu final, total cumulé des remplacements.
        stubHappyPath();
        when(workspaceService.readFile(userId, workspaceId, "notes.txt"))
                .thenReturn("bonjour monde et monde");
        agentProvider.enqueueToolCallWithObject("multi_edit",
                "{\"path\":\"notes.txt\",\"edits\":["
                        + "{\"old_string\":\"monde\",\"new_string\":\"atelier\",\"replace_all\":true},"
                        + "{\"old_string\":\"bonjour\",\"new_string\":\"salut\"}]}");
        agentProvider.enqueueFinal("Modifié.");

        AtelierChatResult result = service.chat(userId, workspaceId, "réécris notes");

        verify(workspaceService).writeFile(userId, workspaceId, "notes.txt", "salut atelier et atelier");
        assertThat(result.actions()).extracting(a -> a.type() + ":" + a.path()).contains("write:notes.txt");
        assertThat(lastToolResultText()).startsWith("Fichier modifié : notes.txt (3 remplacements)");
    }

    @Test
    void multiEditWritesNothingWhenAnyEditFails() {
        // F-121 / SF-121-06 : tout-ou-rien. La 2e édition est introuvable → aucune écriture, le
        // fichier reste intact, et le modèle reçoit une erreur qui situe l'édition fautive.
        stubHappyPath();
        when(workspaceService.readFile(userId, workspaceId, "notes.txt")).thenReturn("bonjour monde");
        agentProvider.enqueueToolCallWithObject("multi_edit",
                "{\"path\":\"notes.txt\",\"edits\":["
                        + "{\"old_string\":\"monde\",\"new_string\":\"atelier\"},"
                        + "{\"old_string\":\"absent\",\"new_string\":\"x\"}]}");
        agentProvider.enqueueFinal("Rien.");

        service.chat(userId, workspaceId, "édite notes");

        verify(workspaceService, never()).writeFile(any(), any(), any(), any());
        assertThat(lastToolResultText()).contains("Édition n°2").contains("introuvable");
    }

    @Test
    void multiEditRefusesAnEmptyEditList() {
        // F-121 / SF-121-06 : un tableau edits vide est refusé sans aucune écriture.
        stubHappyPath();
        when(workspaceService.readFile(userId, workspaceId, "notes.txt")).thenReturn("bonjour monde");
        agentProvider.enqueueToolCallWithObject("multi_edit",
                "{\"path\":\"notes.txt\",\"edits\":[]}");
        agentProvider.enqueueFinal("Rien.");

        service.chat(userId, workspaceId, "édite notes");

        verify(workspaceService, never()).writeFile(any(), any(), any(), any());
        assertThat(lastToolResultText()).contains("edits");
    }

    // ------------------------------------------- F-119 / SF-119-05 + F-121 / SF-121-19 : fraîcheur

    @Test
    void aBlindEditGetsAReadBeforeEditReminder() {
        // Éditer un fichier jamais lu dans ce fil (« à l'aveugle ») déclenche un rappel léger de
        // lecture-avant-édition — sans refuser l'opération (le disque évite déjà la corruption).
        stubHappyPath();
        when(workspaceService.readFile(userId, workspaceId, "notes.txt")).thenReturn("bonjour monde");
        agentProvider.enqueueToolCall("edit_file", "path", "notes.txt",
                "old_string", "monde", "new_string", "atelier");
        agentProvider.enqueueFinal("Modifié.");

        service.chat(userId, workspaceId, "remplace monde");

        assertThat(lastToolResultText())
                .startsWith("Fichier modifié : notes.txt (1 remplacement)")
                .contains("sans l'avoir lu dans ce fil");
    }

    @Test
    void aReadThenEditGetsNoReminder() {
        // Non-régression : lire puis éditer le même fichier ne déclenche AUCUN rappel — c'est
        // exactement le comportement qu'on veut encourager.
        stubHappyPath();
        when(workspaceService.readFile(userId, workspaceId, "notes.txt")).thenReturn("bonjour monde");
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueToolCall("edit_file", "path", "notes.txt",
                "old_string", "monde", "new_string", "atelier");
        agentProvider.enqueueFinal("Modifié.");

        service.chat(userId, workspaceId, "lis puis remplace");

        assertThat(lastToolResultText()).isEqualTo("Fichier modifié : notes.txt (1 remplacement)");
    }

    @Test
    void aReReadOfAChangedFileGetsAChangeNote() {
        // SF-121-19 : relire un fichier dont le contenu a changé depuis la lecture précédente du fil
        // joint une note « ce fichier a changé » au résultat — jamais un refus.
        stubHappyPath();
        when(workspaceService.readFile(userId, workspaceId, "notes.txt"))
                .thenReturn("version 1", "version 2");
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueFinal("Relu.");

        service.chat(userId, workspaceId, "relis notes.txt");

        assertThat(lastToolResultText())
                .contains("version 2")
                .contains("a changé depuis ta lecture précédente");
    }

    @Test
    void theFileStateHintCircuitBreakerSilencesTheReminder() {
        // Coupe-circuit app.atelier.file-state-hints=false (21e arg) : aucune aide-mémoire, même sur
        // une édition à l'aveugle.
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null,
                        null, true, null, null, null, null, null, null, null, false, null));
        stubHappyPath();
        when(workspaceService.readFile(userId, workspaceId, "notes.txt")).thenReturn("bonjour monde");
        agentProvider.enqueueToolCall("edit_file", "path", "notes.txt",
                "old_string", "monde", "new_string", "atelier");
        agentProvider.enqueueFinal("Modifié.");

        service.chat(userId, workspaceId, "remplace monde");

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

    // ------------------------------------------- F-121 / SF-121-01 : grep / glob (cible SANDBOX)

    @Test
    void grepMatchesARegexOnTheHostedStorage() {
        stubHappyPath();
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of("a.txt"));
        when(workspaceService.readFile(userId, workspaceId, "a.txt"))
                .thenReturn("alpha\nBeta TODO-42\ngamma");
        agentProvider.enqueueToolCall("grep", "pattern", "TODO-\\d+");
        agentProvider.enqueueFinal("Trouvé.");

        service.chat(userId, workspaceId, "cherche les TODO");

        assertThat(lastToolResultText()).isEqualTo("a.txt:2: Beta TODO-42\n");
    }

    @Test
    void grepInvalidRegexIsReturnedAsAnErrorNotAStacktrace() {
        stubHappyPath();
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of("a.txt"));
        agentProvider.enqueueToolCall("grep", "pattern", "[unclosed");
        agentProvider.enqueueFinal("Corrigé.");

        service.chat(userId, workspaceId, "grep");

        assertThat(lastToolResultText()).isEqualTo("Expression régulière invalide.");
    }

    @Test
    void globListsMatchingPathsOnTheHostedStorage() {
        stubHappyPath();
        when(workspaceService.tree(userId, workspaceId))
                .thenReturn(List.of("src/main/App.java", "README.md", "src/Util.java"));
        agentProvider.enqueueToolCall("glob", "pattern", "**/*.java");
        agentProvider.enqueueFinal("Listé.");

        service.chat(userId, workspaceId, "trouve les java");

        // Tri par chemin sur la cible hébergée (pas de mtime fiable), formats identiques au runner.
        assertThat(lastToolResultText()).isEqualTo("src/Util.java\nsrc/main/App.java");
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
        verify(quotaService, never()).recordUsage(any(), any(fr.claudegateway.quota.TurnTokens.class),
                any(), any(), any(), any());
    }

    @Test
    void hostedModeChecksAndRecordsQuota() {
        // SF-28-06 : sans clé BYOK (Hosted), la boucle contrôle le quota avant et le comptabilise après.
        stubHappyPath(); // byokKeyService => Optional.empty()
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "salut");

        verify(quotaService).assertWithinQuota(userId);
        verify(quotaService).recordUsage(eq(userId), any(fr.claudegateway.quota.TurnTokens.class),
                any(), org.mockito.ArgumentMatchers.isNull(), any(), any(), any());
    }

    @Test
    void cacheTokensReachTheDecountSeparatelyFromFullPriceInput() {
        // F-63 / SF-63-02 : le fournisseur replie le cache dans l'entrée (D3 de SF-39-01), ce qui
        // faisait facturer au plein tarif des tokens relus au dixième. Le VOLUME ne change pas —
        // 100 000 tokens traités — mais le décompte sait désormais ce qui, dedans, vient du cache.
        stubHappyPath();
        agentProvider.enqueueFinalServedByCache("Bonjour.", 100_000, 500, 90_000, 5_000);

        service.chat(userId, workspaceId, "salut");

        verify(quotaService).recordUsage(userId,
                new fr.claudegateway.quota.TurnTokens(5_000L, 500L, 90_000L, 5_000L),
                fr.claudegateway.quota.TurnExtras.NONE, null, "claude-opus-5", workspaceId, null);
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
        verify(quotaService, never()).recordUsage(any(), any(fr.claudegateway.quota.TurnTokens.class),
                any(), any(), any(), any());
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
        verify(quotaService, never()).recordUsage(any(), any(fr.claudegateway.quota.TurnTokens.class),
                any(), any(), any(), any());
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

    // ------------------------------------------------------------------------------------------
    // F-125 / SF-125-07 — Ceinture « jamais un tour vide » : conserver la réponse déjà produite.
    // ------------------------------------------------------------------------------------------

    @Test
    void emptyFinalTurnTriggersASynthesisPassInsteadOfAPlaceholder() {
        // SF-125-07 : un tour sans aucun texte ne rend plus un placeholder muet — le serveur
        // provoque UNE passe de synthèse forcée, dont la réponse devient celle du tour.
        stubHappyPath();
        agentProvider.enqueueEmptyFinal();                        // le tour ne produit aucun texte
        agentProvider.enqueueFinal("Voici la synthèse du tour."); // la passe de synthèse répond

        AtelierChatResult result = service.chat(userId, workspaceId, "bonjour");

        assertThat(result.reply()).isEqualTo("Voici la synthèse du tour.");
        ArgumentCaptor<AtelierMessage> saved = ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(m -> assertThat(m.getContent()).isNotBlank());
        assertThat(saved.getAllValues())
                .noneSatisfy(m -> assertThat(m.getContent()).contains("Je n'ai pas produit de réponse"));
    }

    @Test
    void aResponseAlreadyProducedIsNeverReplacedByAnEmptyPlumbingIteration() {
        // Cas réel CAGIP : l'agent PRODUIT la bonne réponse (texte + outil), PUIS enchaîne de la
        // plomberie sans texte, PUIS un tour final vide. La réponse du tour doit rester le texte
        // déjà vu par l'utilisateur — jamais le vide.
        stubHappyPath();
        String bonneReponse = "<<essentiel>>L'URL est dupliquée.<</essentiel>>\nVoici le détail du diagnostic.";
        agentProvider.enqueueToolCallWithText(bonneReponse, "read_file", "path", "list_roles.go");
        agentProvider.enqueueToolCall("edit_file", "path", "acces.md",
                "old_string", "contenu du fichier", "new_string", "rangé");
        agentProvider.enqueueEmptyFinal();

        RecordingListener listener = new RecordingListener();
        AtelierChatResult result =
                service.chatStreaming(userId, workspaceId, "ça s'est bien passé ?", listener);

        // La réponse finale conservée = le texte déjà produit, pas le vide.
        assertThat(result.reply()).isEqualTo(bonneReponse);
        // SSE et persistance convergent : ce qui a défilé contient la réponse, et la base la porte.
        assertThat(listener.texts).anySatisfy(t -> assertThat(t).contains("L'URL est dupliquée."));
        ArgumentCaptor<AtelierMessage> saved = ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, atLeastOnce()).save(saved.capture());
        AtelierMessage assistant = saved.getAllValues().stream()
                .filter(m -> "ASSISTANT".equals(m.getRole())).reduce((a, b) -> b).orElseThrow();
        assertThat(assistant.getContent()).isEqualTo(bonneReponse);
        assertThat(saved.getAllValues())
                .noneSatisfy(m -> assertThat(m.getContent()).contains("Je n'ai pas produit de réponse"));
    }

    @Test
    void aTurnWhoseOnlyTextIsAMarkerStrippedToBlankTriggersTheSynthesis() {
        // SF-125-07 : « vide après strip » — un tour dont le seul texte est le marqueur fin-de-tour
        // (retiré par stripTurnMetadata, SF-125-01) est traité comme sans texte → synthèse forcée.
        stubHappyPath();
        agentProvider.enqueueFinal("<!-- fin-de-tour: promu=x -> y.md -->");
        agentProvider.enqueueFinal("Réponse de synthèse.");

        AtelierChatResult result = service.chat(userId, workspaceId, "réponds");

        assertThat(result.reply()).isEqualTo("Réponse de synthèse.");
    }

    @Test
    void theSynthesisPassOffersNoToolsAndRunsOnlyOnce() {
        // SF-125-07 : borne anti-boucle. La synthèse est UNIQUE et SANS outils (« pas de plomberie ») :
        // le dernier appel fournisseur (la synthèse) ne se voit offrir aucun outil.
        stubHappyPath();
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt"); // 1er appel : plomberie
        agentProvider.enqueueEmptyFinal();                               // 2e appel : rien
        agentProvider.enqueueFinal("Synthèse.");                         // 3e appel : la synthèse

        service.chat(userId, workspaceId, "fais quelque chose");

        // Exactement 3 appels fournisseur (2 boucle + 1 synthèse), script épuisé : aucune récursion.
        assertThat(agentProvider.remaining()).isZero();
        assertThat(agentProvider.toolBelts).hasSize(3);
        // Les deux tours de boucle voient la panoplie ; la synthèse (dernier appel) n'a aucun outil.
        assertThat(agentProvider.toolBelts.get(0)).isNotEmpty();
        assertThat(agentProvider.toolBelts.get(2)).isEmpty();
    }

    @Test
    void whenTheSynthesisAlsoProducesNothingAnHonestLastResortIsPersisted() {
        // SF-125-07 : si la synthèse ne rend toujours rien, un message de dernier recours honnête et
        // actionnable — jamais l'ancien placeholder muet.
        stubHappyPath();
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueEmptyFinal(); // tour vide
        agentProvider.enqueueEmptyFinal(); // la synthèse ne rend rien non plus

        AtelierChatResult result = service.chat(userId, workspaceId, "conclus");

        assertThat(result.reply()).isEqualTo(AtelierChatService.LAST_RESORT_REPLY);
        assertThat(result.reply()).doesNotContain("Je n'ai pas produit de réponse");
    }

    // ------------------------------------------------------------------------------------------
    // F-125 / SF-125-08 — Conserver la réponse de fond, jamais la plomberie de carte.
    // ------------------------------------------------------------------------------------------

    /** La note de plomberie de carte du cas réel CAGIP (« déjà dans la carte », ~140 car). */
    private static final String PLUMBING_NOTE =
            "Vérifié : la clé privée versionnée est déjà dans la carte, en `acces.md:465` ; "
                    + "rien à promouvoir, le rangement est à jour côté gouvernance.";

    @Test
    void theEssentialAnswerIsKeptEvenWhenAPlumbingNoteIsEmittedLater() {
        // Cas réel CAGIP : l'agent produit l'essentiel+détail TÔT (avec un outil), puis FINIT le tour
        // sur une note de plomberie de carte. SF-125-07 gardait « le dernier texte non vide » → la
        // plomberie. SF-125-08 garde le TEXTE DE FOND → l'essentiel doit survivre à la plomberie.
        stubHappyPath();
        String fond = "<<essentiel>>Les mails sont tracés dans `logs/mail.go:12`.<</essentiel>>\n"
                + "Voici le détail : le traceur écrit une ligne par envoi.";
        agentProvider.enqueueToolCallWithText(fond, "read_file", "path", "logs/mail.go");
        agentProvider.enqueueFinal(PLUMBING_NOTE); // dernier texte NON vide = plomberie de carte

        RecordingListener listener = new RecordingListener();
        AtelierChatResult result =
                service.chatStreaming(userId, workspaceId, "trace les mails là", listener);

        // La réponse conservée = l'essentiel+détail, jamais la plomberie.
        assertThat(result.reply()).isEqualTo(fond);
        assertThat(result.reply()).doesNotContain("déjà dans la carte");
        assertThat(result.reply()).doesNotContain("acces.md:465");
        // SSE (done) et persistance convergent sur la même valeur : le message assistant persisté = le fond.
        ArgumentCaptor<AtelierMessage> saved = ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, atLeastOnce()).save(saved.capture());
        AtelierMessage assistant = saved.getAllValues().stream()
                .filter(m -> "ASSISTANT".equals(m.getRole())).reduce((a, b) -> b).orElseThrow();
        assertThat(assistant.getContent()).isEqualTo(fond);
        assertThat(assistant.getContent()).doesNotContain("acces.md:465");
    }

    @Test
    void aPlumbingNoteAloneNeverBecomesTheReplyAndFallsBackToSynthesis() {
        // F-125-05 côté serveur : un tour dont le seul texte est une note de plomberie de carte ne
        // promeut JAMAIS cette plomberie en réponse — il joue la synthèse forcée (SF-125-07) à la place.
        stubHappyPath();
        agentProvider.enqueueToolCall("read_file", "path", "acces.md"); // plomberie sans texte
        agentProvider.enqueueFinal(PLUMBING_NOTE);                      // dernier tour : plomberie seule
        agentProvider.enqueueFinal("Les mails sont tracés dans logs/mail.go."); // la synthèse répond

        AtelierChatResult result = service.chat(userId, workspaceId, "trace les mails là");

        assertThat(result.reply()).isEqualTo("Les mails sont tracés dans logs/mail.go.");
        assertThat(result.reply()).doesNotContain("déjà dans la carte");
    }

    @Test
    void whenSeveralEssentialsAreEmittedTheLastOneIsKept() {
        // Plusieurs essentiels dans le tour → le DERNIER est gardé (c'est une réponse de fond affinée,
        // pas de la plomberie) ; une note de plomberie postérieure ne l'évince pas davantage.
        stubHappyPath();
        String premier = "<<essentiel>>Réponse provisoire.<</essentiel>>\nDétail initial.";
        String dernier = "<<essentiel>>Réponse corrigée : c'est bien logs/mail.go:12.<</essentiel>>\nDétail final.";
        agentProvider.enqueueToolCallWithText(premier, "read_file", "path", "a.go");
        agentProvider.enqueueToolCallWithText(dernier, "read_file", "path", "b.go");
        agentProvider.enqueueFinal(PLUMBING_NOTE);

        AtelierChatResult result = service.chat(userId, workspaceId, "où sont tracés les mails ?");

        assertThat(result.reply()).isEqualTo(dernier);
        assertThat(result.reply()).doesNotContain("provisoire");
    }

    @Test
    void aSubstantialAnswerWithoutEssentialIsNotEvictedByALaterPlumbingNote() {
        // Sans marqueur essentiel : on garde le dernier texte SUBSTANTIEL ; une note de plomberie de
        // carte émise ensuite ne le remplace pas (rétention par substance, pas par ordre).
        stubHappyPath();
        String fond = "Le rôle admin ouvre tout par défaut, c'est porté par le service de droits.";
        agentProvider.enqueueToolCallWithText(fond, "read_file", "path", "roles.go");
        agentProvider.enqueueFinal(PLUMBING_NOTE);

        AtelierChatResult result = service.chat(userId, workspaceId, "l'admin a-t-il tous les droits ?");

        assertThat(result.reply()).isEqualTo(fond);
        assertThat(result.reply()).doesNotContain("déjà dans la carte");
    }

    @Test
    void hasEssentialDetectsTheMarkerAndItsAbsence() {
        // Miroir serveur de splitEssential (F-126) : détection tolérante à la casse et aux espaces.
        assertThat(AtelierChatService.hasEssential("<<essentiel>>La réponse.<</essentiel>>")).isTrue();
        assertThat(AtelierChatService.hasEssential("<< Essentiel >>La réponse.")).isTrue(); // sans fermeture
        assertThat(AtelierChatService.hasEssential("Une réponse sans marqueur.")).isFalse();
        assertThat(AtelierChatService.hasEssential("<<essentiel>>  <</essentiel>>")).isFalse(); // vide
        assertThat(AtelierChatService.hasEssential(null)).isFalse();
    }

    @Test
    void isCardPlumbingIsConservativeAndNeverHidesARealAnswer() {
        // Drapeau « plomberie » : petit + formule connue + absence d'essentiel. Au moindre doute, faux.
        // Vrais positifs (formules de plomberie de carte connues) :
        assertThat(AtelierChatService.isCardPlumbing(PLUMBING_NOTE)).isTrue();          // « déjà dans la carte »
        assertThat(AtelierChatService.isCardPlumbing("acces.md:465")).isTrue();         // simple référence
        assertThat(AtelierChatService.isCardPlumbing("Le fait est déjà rangé dans `acces.md`.")).isTrue();
        assertThat(AtelierChatService.isCardPlumbing("Déjà dans `acces.md`, rien à faire.")).isTrue();
        // Faux (on garde le texte) — contrainte PO : ne jamais masquer une vraie réponse :
        assertThat(AtelierChatService.isCardPlumbing("La réponse est 42.")).isFalse();
        assertThat(AtelierChatService.isCardPlumbing("<<essentiel>>Déjà dans acces.md.<</essentiel>>")).isFalse();
        // « déjà rangé » sans indice de carte : une vraie réponse, gardée.
        assertThat(AtelierChatService.isCardPlumbing("Les mails sont déjà rangés par date dans la boîte."))
                .isFalse();
        // Texte long mentionnant .md et « déjà dans » : trop long pour un statut, c'est une réponse.
        assertThat(AtelierChatService.isCardPlumbing(
                "La procédure est déjà dans notes.md, et voici pourquoi : " + "détail. ".repeat(60)))
                .isFalse();
        assertThat(AtelierChatService.isCardPlumbing(null)).isFalse();
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
                runnerHostService,
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

    // ------------------------------------------------- SF-84-04 : chaque outil se montre

    @Test
    void anExplorationShowsItsQuestionBeforeItRuns() {
        stubHappyPath();
        agentProvider.enqueueToolCall("explore", "question", "où est défini AppConfig ?");
        agentProvider.enqueueFinal("Dans src/AppConfig.java.");
        agentProvider.enqueueFinal("Voilà.");
        RecordingListener listener = new RecordingListener();

        service.chatStreaming(userId, workspaceId, "où est AppConfig ?", listener);

        // Une délégation peut durer des minutes : sans étape, l'écran restait muet tout du long.
        assertThat(listener.actions)
                .containsExactly(new AtelierStepEvent("explore", "où est défini AppConfig ?"));
    }

    @Test
    void aToolWithoutADedicatedLabelStillShowsWhenItStarts() {
        stubHappyPath();
        agentProvider.enqueueToolCall("teams_read_thread", "threadId", "19:abc");
        agentProvider.enqueueFinal("Lu.");
        RecordingListener listener = new RecordingListener();

        service.chatStreaming(userId, workspaceId, "lis le fil", listener);

        assertThat(listener.actions).extracting(AtelierStepEvent::type)
                .containsExactly("teams_read_thread");
    }

    @Test
    void thePlanToolHasItsOwnDisplayAndNoStep() {
        stubHappyPath();
        agentProvider.enqueueToolCallWithJson("set_plan", "steps", "[{\"title\":\"A\"}]");
        agentProvider.enqueueFinal("Fait.");
        RecordingListener listener = new RecordingListener();

        service.chatStreaming(userId, workspaceId, "vas-y", listener);

        assertThat(listener.actions).isEmpty();
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
        // SF-39-20 : et ce qu'elle A, dit positivement. L'assertion négative ci-dessus restait vraie
        // sur une panoplie vide — c'est ainsi que l'exploration a pu se retrouver avec un seul
        // outil en cible RUNNER sans qu'aucun test bronche. La panoplie est la même ici (D2).
        assertThat(agentProvider.toolBelts.get(1))
                .containsExactly("list_files", "read_file", "search_files", "grep", "glob");
    }

    @Test
    void theExplorationInvestigatesWithNonZeroReasoning() {
        // F-119 / SF-119-01 : la sous-boucle d'exploration ne part plus avec AgentReasoning.none()
        // (raisonnement zéro), mais avec un raisonnement adaptatif à effort configurable non nul
        // (défaut `low`) — elle lit ET interprète.
        stubHappyPath();
        agentProvider.enqueueToolCall("explore", "question", "cherche");
        agentProvider.enqueueFinal("Trouvé.");
        agentProvider.enqueueFinal("Voilà.");

        service.chat(userId, workspaceId, "cherche");

        // L'appel d'index 1 est le premier de la sous-boucle (l'index 0 est la boucle principale) :
        // son raisonnement est adaptatif et non nul.
        assertThat(agentProvider.reasoningSnapshots.get(1))
                .isEqualTo(new AgentReasoning(true, "low"));
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
                runnerHostService,
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

    // ------------------------------------------------- SF-39-17 : un tour long ne se perd plus

    @Test
    void persistsTheTranscriptSoARefreshStillShowsWhatHappened() {
        stubHappyPath();
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueFinal("Lu.");

        service.chat(userId, workspaceId, "lis notes.txt");

        ArgumentCaptor<AtelierMessage> saved = ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, atLeastOnce()).save(saved.capture());
        String json = saved.getAllValues().stream()
                .map(AtelierMessage::getTerminalJson)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse("");
        // C'est ce que l'écran relit après un rechargement : sans lui, une coupure de connexion
        // effaçait tout ce qui s'était passé.
        assertThat(json).contains("read_file").contains("blocks");
    }

    @Test
    void keepsTheEndOfALongOutputBecauseThatIsWhereTheErrorIs() {
        String head = "a".repeat(6_000);
        AtelierTurnReport.Block block = new AtelierTurnReport.Block("bash", "mvn test", "t1", null,
                head + "BUILD FAILURE", true, true, false);

        AtelierTurnReport report = new AtelierTurnReport(10, 10, 1, false, false, AtelierPlan.EMPTY,
                List.of(block));

        // La fin est conservée : code de sortie, message d'erreur, dernière ligne de pile.
        assertThat(report.toJson()).contains("BUILD FAILURE").contains("début tronqué");
    }

    @Test
    void countsTheBlocksItHadToDrop() {
        List<AtelierTurnReport.Block> many = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            many.add(new AtelierTurnReport.Block("bash", "cmd " + i, "t" + i, null, "ok", true, false, false));
        }

        AtelierTurnReport report = new AtelierTurnReport(10, 10, 1, false, false, AtelierPlan.EMPTY, many);

        assertThat(report.blocks()).hasSize(AtelierTurnReport.MAX_BLOCKS);
        assertThat(report.omittedBlocks()).isEqualTo(50);
        // Les DERNIERS blocs : quand un tour a été coupé, c'est la fin qui explique pourquoi.
        assertThat(report.toJson()).contains("cmd 249").doesNotContain("cmd 0\"");
    }

    @Test
    void anInterruptedTurnKeepsItsPartialTranscript() {
        stubHappyPath();
        agentProvider.enqueueToolCall("read_file", "path", "a.txt");
        agentProvider.enqueueFinal("Fini.");

        service.chat(userId, workspaceId, "lis");

        ArgumentCaptor<AtelierMessage> saved = ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anySatisfy(m ->
                assertThat(m.getTerminalJson()).isNotNull());
    }

    // ------------------------------------------------- SF-39-19 : parler pendant qu'il travaille

    /**
     * Le tour vivant vu par la boucle (F-84 / SF-84-06) : une file de précisions que la boucle prend
     * à chaque étape, et le relevé de l'étape à laquelle chacune a été prise en compte.
     */
    private static final class SteeringListener implements AtelierProgressListener {
        final java.util.Deque<AtelierSteer> queue = new java.util.ArrayDeque<>();
        final List<String> applied = new ArrayList<>();
        int takes;

        void deposit(String text) {
            queue.addLast(new AtelierSteer("s-" + (queue.size() + applied.size() + 1), text));
        }

        @Override
        public List<AtelierSteer> takeSteers() {
            takes++;
            List<AtelierSteer> taken = List.copyOf(queue);
            queue.clear();
            return taken;
        }

        @Override
        public void onSteerApplied(AtelierSteer steer, int step) {
            applied.add(steer.text() + "@" + step);
        }

        @Override
        public void onAction(AtelierStepEvent step) {
            // sans objet
        }

        @Override
        public void onText(String text) {
            // sans objet
        }
    }

    @Test
    void aSteerDepositedDuringStepOneIsSentAtStepTwoAfterTheToolResults() {
        stubHappyPath();
        SteeringListener listener = new SteeringListener();
        // Déposée PENDANT l'appel de l'étape 1, comme dans la vraie vie.
        agentProvider.onTurn(() -> listener.deposit("en fait, saute les tests"));
        agentProvider.enqueueToolCall("read_file", "path", "a.txt");
        agentProvider.enqueueFinal("Compris.");

        service.chatStreaming(userId, workspaceId, "construis le projet", listener);

        assertThat(agentProvider.messageSnapshots).hasSize(2);
        assertThat(agentProvider.messageSnapshots.get(0)).doesNotContain("saute les tests");
        String step2 = agentProvider.messageSnapshots.get(1);
        assertThat(step2).contains("saute les tests");
        assertThat(step2.indexOf("ToolResult")).as("après le résultat de l'outil en cours")
                .isLessThan(step2.indexOf("saute les tests"));
        assertThat(listener.applied).containsExactly("en fait, saute les tests@2");
    }

    @Test
    void aSteerIsAddedOnlyOnce() {
        stubHappyPath();
        SteeringListener listener = new SteeringListener();
        agentProvider.onTurn(() -> listener.deposit("précision unique"));
        agentProvider.enqueueToolCall("read_file", "path", "a.txt");
        agentProvider.enqueueToolCall("read_file", "path", "b.txt");
        agentProvider.enqueueFinal("Fait.");

        service.chatStreaming(userId, workspaceId, "vas-y", listener);

        // Consommée : elle ne doit pas être réinjectée à chaque itération.
        long occurrences = agentProvider.lastRequest.messages().stream()
                .filter(m -> m.content().toString().contains("précision unique"))
                .count();
        assertThat(occurrences).isEqualTo(1);
    }

    @Test
    void severalSteersArriveInTheOrderTheyWereDepositedAndArePersistedInThatOrder() {
        stubHappyPath();
        SteeringListener listener = new SteeringListener();
        agentProvider.onTurn(() -> {
            listener.deposit("première");
            listener.deposit("seconde");
        });
        agentProvider.enqueueToolCall("read_file", "path", "a.txt");
        agentProvider.enqueueFinal("Vu.");

        service.chatStreaming(userId, workspaceId, "vas-y", listener);

        String sent = agentProvider.lastRequest.messages().toString();
        assertThat(sent.indexOf("première")).isLessThan(sent.indexOf("seconde"));
        assertThat(listener.applied).containsExactly("première@2", "seconde@2");
        // Persistées à leur place : la demande, les deux précisions, puis la réponse du tour.
        ArgumentCaptor<AtelierMessage> saved = ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(m -> m.getRole() + ":" + m.getContent())
                .containsExactly("USER:vas-y", "USER:première", "USER:seconde", "ASSISTANT:Vu.");
    }

    @Test
    void aSteerQueuedBeforeTheFirstStepIsReadAtStepOne() {
        stubHappyPath();
        SteeringListener listener = new SteeringListener();
        // Le tour de suite (SF-84-06) : les précisions restantes attendent déjà en file.
        listener.deposit("et le changelog");
        agentProvider.enqueueFinal("Fait.");

        service.chatStreaming(userId, workspaceId, "et ajoute un test", listener);

        assertThat(agentProvider.messageSnapshots.get(0)).contains("et le changelog");
        assertThat(listener.applied).containsExactly("et le changelog@1");
    }

    @Test
    void aSteerDepositedDuringTheFinalAnswerStaysInTheLiveTurn() {
        stubHappyPath();
        SteeringListener listener = new SteeringListener();
        // Déposée pendant l'appel qui rend la réponse finale : plus d'étape pour la lire. La boucle
        // ne la consomme pas — c'est le tour vivant qui ouvrira le tour de suite.
        agentProvider.onTurn(() -> listener.deposit("précision tardive"));
        agentProvider.enqueueFinal("Un.");

        service.chatStreaming(userId, workspaceId, "premier", listener);

        assertThat(listener.queue).extracting(AtelierSteer::text).containsExactly("précision tardive");
        assertThat(listener.applied).isEmpty();
    }

    @Test
    void aTurnWithoutSteerIsUnchanged() {
        stubHappyPath();
        agentProvider.enqueueFinal("Voilà.");

        assertThat(service.chat(userId, workspaceId, "vas-y").reply()).isEqualTo("Voilà.");
    }
}
