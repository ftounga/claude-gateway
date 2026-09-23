package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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

import fr.claudegateway.agent.AgentTurnMode;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * Plan mode piloté par le modèle + persistance du plan par thread (F-121 / SF-121-10).
 *
 * <p>Trois garanties : (a) {@code exit_plan_mode} soumet un plan à approbation ({@code planSubmitted},
 * plan rendu à l'écran) <b>sans</b> alimenter {@code planOfTurn} (pas d'interaction avec la porte de
 * complétude SF-121-05) ; (b) le mode et le dernier plan encore actif sont <b>persistés</b> en fin de
 * tour ; (c) un plan persisté est <b>réinjecté dans la consigne</b> du tour suivant — jamais dans la
 * consigne système (cache F-134 préservé) — et rendu à l'écran.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServicePlanPersistenceTest {

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
    private RecordingStore store;

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
                runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));
        store = new RecordingStore();
        service.setThreadStateStore(store);

        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        lenient().when(quotaService.currentUsage(userId)).thenReturn(
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
    }

    /** Projet hébergé (SANDBOX) prêt pour un tour trivial : ni CLAUDE.md ni arbre. */
    private Workspace stubSandbox() {
        Workspace sandbox = new Workspace();
        sandbox.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        sandbox.setId(workspaceId);
        sandbox.setUserId(userId);
        sandbox.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(sandbox);
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));
        return sandbox;
    }

    // ---------------------------------------------------------------- (a) exit_plan_mode

    @Test
    void exitPlanModeSubmitsThePlanForApprovalAndPersistsIt() {
        stubSandbox();
        agentProvider.enqueueToolCallWithObject("exit_plan_mode", """
                {"steps":[{"title":"Générer le backend","status":"pending"},
                          {"title":"Lancer les builds","status":"pending"}]}""");
        agentProvider.enqueueFinal("Voici mon plan, à toi de valider.");
        RecordingListener listener = new RecordingListener();

        AtelierChatResult result = service.chatStreaming(userId, workspaceId,
                "que ferais-tu ?", AgentTurnMode.ANSWER_PLAN, listener);

        // Le tour est marqué « plan soumis » : l'écran proposera « Approuver & exécuter ».
        assertThat(result.planSubmitted()).isTrue();
        // Le plan a été rendu à l'écran.
        assertThat(listener.lastPlan()).isNotNull();
        assertThat(listener.lastPlan().steps()).extracting(AtelierPlan.Step::title)
                .contains("Générer le backend", "Lancer les builds");
        // Il a été persisté, avec le mode du tour.
        assertThat(store.called).isTrue();
        assertThat(store.mode).isEqualTo("ANSWER_PLAN");
        assertThat(store.planJson).contains("Générer le backend");
    }

    @Test
    void exitPlanModeWithoutStepsIsNotASubmission() {
        stubSandbox();
        agentProvider.enqueueToolCallWithObject("exit_plan_mode", "{\"steps\":[]}");
        agentProvider.enqueueFinal("Je n'ai pas encore de plan.");

        AtelierChatResult result = service.chatStreaming(userId, workspaceId,
                "?", AgentTurnMode.ANSWER_PLAN, new RecordingListener());

        assertThat(result.planSubmitted()).isFalse();
        // Aucun plan à reporter : la colonne reste vide.
        assertThat(store.planJson).isNull();
    }

    // ---------------------------------------------------------------- (b) persistance

    @Test
    void setPlanIsPersistedAtEndOfTurnWithTheActModeStoredAsNull() {
        stubSandbox();
        agentProvider.enqueueToolCallWithObject("set_plan", """
                {"steps":[{"title":"Étape 1","status":"active"}]}""");
        agentProvider.enqueueFinal("En cours.");

        service.chatStreaming(userId, workspaceId, "corrige le bug", AgentTurnMode.ACT,
                new RecordingListener());

        assertThat(store.called).isTrue();
        assertThat(store.mode).isNull(); // ACT = défaut, stocké null (rétrocompat).
        assertThat(store.planJson).contains("Étape 1");
    }

    @Test
    void aFullyCompletedPlanIsNotCarriedOver() {
        stubSandbox();
        agentProvider.enqueueToolCallWithObject("set_plan", """
                {"steps":[{"title":"Fini","status":"done"}]}""");
        agentProvider.enqueueFinal("Terminé.");

        service.chatStreaming(userId, workspaceId, "fais X", AgentTurnMode.ACT, new RecordingListener());

        // Rien à reprendre : la colonne est remise à null.
        assertThat(store.planJson).isNull();
    }

    @Test
    void anUntouchedCarriedPlanIsKept() {
        Workspace sandbox = stubSandbox();
        sandbox.setChatThreadPlan("[{\"title\":\"Reste à faire\",\"status\":\"pending\"}]");
        agentProvider.enqueueFinal("Bonjour.");

        service.chatStreaming(userId, workspaceId, "bonjour", AgentTurnMode.ACT, new RecordingListener());

        // Le modèle n'a pas touché son plan : on garde le plan reporté tel quel.
        assertThat(store.planJson).contains("Reste à faire");
    }

    // ---------------------------------------------------------------- (c) réinjection

    @Test
    void aCarriedPlanIsReinjectedIntoTheConsigneNotTheSystemPromptAndRendered() {
        Workspace sandbox = stubSandbox();
        sandbox.setChatThreadPlan("[{\"title\":\"Poursuivre la migration\",\"status\":\"active\"}]");
        agentProvider.enqueueFinal("Je reprends.");
        RecordingListener listener = new RecordingListener();

        service.chatStreaming(userId, workspaceId, "où en es-tu ?", AgentTurnMode.ACT, listener);

        // Le plan reporté est dans la CONSIGNE (le message utilisateur du tour)…
        String lastUserMessage = agentProvider.messageSnapshots.get(0);
        assertThat(lastUserMessage).contains("Poursuivre la migration");
        // …mais JAMAIS dans la consigne système (cache F-134 préservé).
        assertThat(agentProvider.lastRequest.system()).doesNotContain("Poursuivre la migration");
        // Il est aussi rendu à l'écran dès l'ouverture du tour.
        assertThat(listener.lastPlan()).isNotNull();
        assertThat(listener.lastPlan().steps()).extracting(AtelierPlan.Step::title)
                .contains("Poursuivre la migration");
    }

    @Test
    void withoutACarriedPlanTheConsigneAndSystemAreUnchanged() {
        stubSandbox();
        agentProvider.enqueueFinal("ok");

        service.chatStreaming(userId, workspaceId, "salut", AgentTurnMode.ACT, new RecordingListener());

        // Aucun préfixe de plan n'est ajouté quand il n'y a rien à reporter (rétrocompat).
        assertThat(agentProvider.messageSnapshots.get(0)).doesNotContain("plan de travail en cours");
    }

    /** Store enregistreur : capture ce qui est persisté, sans base. */
    static final class RecordingStore implements AtelierThreadStateStore {
        volatile boolean called;
        volatile String mode;
        volatile String planJson;

        @Override
        public void persist(UUID userId, UUID workspaceId, String mode, String planJson) {
            this.called = true;
            this.mode = mode;
            this.planJson = planJson;
        }
    }

    /** Écouteur enregistreur : capture les plans rendus à l'écran. */
    static final class RecordingListener implements AtelierProgressListener {
        private final List<AtelierPlan> plans = new ArrayList<>();

        @Override
        public void onAction(AtelierStepEvent step) {
        }

        @Override
        public void onText(String text) {
        }

        @Override
        public void onPlan(AtelierPlan plan) {
            plans.add(plan);
        }

        AtelierPlan lastPlan() {
            return plans.isEmpty() ? null : plans.get(plans.size() - 1);
        }
    }
}
