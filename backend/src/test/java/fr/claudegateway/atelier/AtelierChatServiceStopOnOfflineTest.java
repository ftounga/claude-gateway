package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.runner.door.RunnerStopSummary;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>L'arrêt net</b> (F-161 / SF-161-02) : quand le poste tombe <b>en plein tour</b>, la boucle
 * s'arrête sans rappeler le fournisseur.
 *
 * <p>Ce que ces tests tiennent, et qui est toute la valeur de la subfeature : <b>le fournisseur
 * n'est appelé qu'une fois</b>. Sans l'arrêt, il l'est une seconde fois — un appel complet, sur
 * tout le contexte, pour que le modèle écrive « Non concluant », c'est-à-dire ce que la gateway
 * savait déjà. C'est le gain que le cadrage F-161 §5 appelle « le plus gros ».</p>
 *
 * <p>La règle de F-93 / SF-93-04 est préservée : <b>le dernier appel fait foi</b>. Un poste revenu
 * en cours de tour n'arrête rien.</p>
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceStopOnOfflineTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private fr.claudegateway.byok.ByokKeyService byokKeyService;
    @Mock private fr.claudegateway.quota.QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private fr.claudegateway.runner.exec.RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private fr.claudegateway.runner.exec.RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;
    @Mock private RunnerHostService runnerHostService;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, true));
        lenient().when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runnerWorkspace());
        lenient().when(runnerHostService.hostName(hostId)).thenReturn("CAGIP");
        lenient().when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(java.util.Optional.empty());
        lenient().when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        lenient().when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(java.util.List.of());
        lenient().when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
    }

    private Workspace runnerWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        return workspace;
    }

    private static RunnerCallResult okCall() {
        return new RunnerCallResult(true, "fait", false, 0, 12L, 4L, null, null, "", false);
    }

    /** Le refus de TRANSPORT : la gateway l'émet elle-même, le runner n'a rien répondu. */
    private static RunnerCallResult offlineCall() {
        return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE);
    }

    /** Le poste répond — ou ne répond plus — au prochain appel d'outil. */
    private void runnerAnswers(boolean reachable) {
        when(runnerToolGateway.bash(any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(reachable ? okCall() : offlineCall());
    }

    @Test
    @DisplayName("poste décroché en plein tour : LE FOURNISSEUR N'EST PAS RAPPELÉ")
    void theProviderIsNotCalledAgain() {
        agentProvider.enqueueToolCall("bash", "command", "mvn test");
        agentProvider.enqueueFinal("voici mon analyse de la panne");
        runnerAnswers(false);

        AtelierChatService.AtelierChatResult result =
                service.chat(userId, workspaceId, "lance les tests");

        assertThat(agentProvider.messageSnapshots)
                .as("UN SEUL appel : le second aurait payé tout le contexte pour « Non concluant »")
                .hasSize(1);
        assertThat(result.stoppedByMachine()).isTrue();
        assertThat(result.reply()).startsWith(RunnerStopSummary.PREFIX);
        assertThat(result.reply()).contains("CAGIP");
    }

    @Test
    @DisplayName("l'arrêt net est une issue DISTINCTE de l'interruption de l'utilisateur")
    void itIsNotAUserInterruption() {
        agentProvider.enqueueToolCall("bash", "command", "mvn test");
        agentProvider.enqueueFinal("jamais atteint");
        runnerAnswers(false);

        AtelierChatService.AtelierChatResult result =
                service.chat(userId, workspaceId, "lance les tests");

        // Trois causes différentes — geste de l'utilisateur, incident de machine, panne
        // fournisseur — que l'écran doit pouvoir distinguer.
        assertThat(result.stoppedByMachine()).isTrue();
        assertThat(result.interrupted()).isFalse();
    }

    @Test
    @DisplayName("poste REVENU dans la MÊME étape : rien ne s'arrête — le dernier appel fait foi")
    void aRecoveredRunnerStopsNothing() {
        // La récupération ne peut se produire QUE dans une même étape, entre deux appels d'outils
        // du même tour d'assistant. D'une étape à l'autre, il faudrait rappeler le fournisseur —
        // c'est-à-dire précisément la dépense que l'arrêt net évite. La règle de F-93 / SF-93-04
        // n'est donc pas affaiblie : elle s'applique là où elle peut encore s'appliquer.
        agentProvider.enqueueToolCalls("bash", "command", "premier", "second");
        agentProvider.enqueueFinal("tout va bien");
        when(runnerToolGateway.bash(any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(offlineCall())
                .thenReturn(okCall());

        AtelierChatService.AtelierChatResult result =
                service.chat(userId, workspaceId, "lance les tests");

        assertThat(result.stoppedByMachine())
                .as("un poste revenu repasse joignable — le dernier appel fait foi")
                .isFalse();
        assertThat(result.reply()).isEqualTo("tout va bien");
        assertThat(agentProvider.messageSnapshots).hasSize(2);
    }

    @Test
    @DisplayName("poste vivant tout le tour : rien ne change")
    void ahealthyTurnIsUntouched() {
        agentProvider.enqueueToolCall("bash", "command", "mvn test");
        agentProvider.enqueueFinal("les tests passent");
        runnerAnswers(true);

        AtelierChatService.AtelierChatResult result =
                service.chat(userId, workspaceId, "lance les tests");

        assertThat(result.stoppedByMachine()).isFalse();
        assertThat(result.reply()).isEqualTo("les tests passent");
    }

    @Test
    @DisplayName("réglage à false : la boucle se comporte EXACTEMENT comme avant")
    void theStopIsSwitchable() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "stopOnRunnerOffline", false);
        agentProvider.enqueueToolCall("bash", "command", "mvn test");
        agentProvider.enqueueFinal("voici mon analyse de la panne");
        runnerAnswers(false);

        AtelierChatService.AtelierChatResult result =
                service.chat(userId, workspaceId, "lance les tests");

        assertThat(result.stoppedByMachine()).isFalse();
        assertThat(result.reply()).isEqualTo("voici mon analyse de la panne");
        assertThat(agentProvider.messageSnapshots)
                .as("sans l'arrêt, le second appel a bien lieu — c'est lui qu'on économise")
                .hasSize(2);
    }

    @Test
    @DisplayName("le message d'arrêt NOMME ce qui avait abouti — pas seulement l'échec")
    void whatSucceededIsKept() {
        agentProvider.enqueueToolCall("bash", "command", "git status");
        agentProvider.enqueueToolCall("bash", "command", "mvn test");
        agentProvider.enqueueFinal("jamais atteint");
        when(runnerToolGateway.bash(any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(okCall())
                .thenReturn(offlineCall());

        AtelierChatService.AtelierChatResult result =
                service.chat(userId, workspaceId, "lance les tests");

        assertThat(result.stoppedByMachine()).isTrue();
        assertThat(result.reply()).contains("- bash");
        assertThat(result.reply()).contains("le travail ci-dessus est conservé");
    }
}
