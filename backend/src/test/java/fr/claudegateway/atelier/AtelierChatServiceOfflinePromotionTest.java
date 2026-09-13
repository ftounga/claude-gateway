package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import fr.claudegateway.atelier.checkpoint.AtelierCheckpoint;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.atelier.checkpoint.AtelierMachineReach;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.governance.GovernanceControl;
import fr.claudegateway.governance.GovernanceMapDestinations;
import fr.claudegateway.governance.control.JugeFinDeTourControl;
import fr.claudegateway.governance.control.PromotionDetteBloquanteControl;
import fr.claudegateway.governance.control.PromotionReportee;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * <b>Runner hors ligne : la promotion est reportée, pas exigée</b> — dans la boucle réelle, avec les
 * contrôles réels du premier paquet (F-93 / SF-93-04).
 *
 * <p>Le constat de production : runner déconnecté, trois refus de fin de tour qui redemandent
 * d'écrire dans la carte, trois réponses « je ne peux pas écrire ». Ces tests protègent la sortie :
 * zéro refus en boucle, une mention unique — et la dette réclamée quand le poste revient.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceOfflinePromotionTest {

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
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;
    @Mock private GovernanceMapDestinations destinations;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private final List<AtelierCheckpointContext> seen = new ArrayList<>();

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final fr.claudegateway.runner.channel.RunnerTarget runnerTarget =
            new fr.claudegateway.runner.channel.RunnerTarget(hostId, workspaceId, "projet");

    private static final String PROMOTION_DUE = "Je ne peux pas écrire dans la carte : le runner "
            + "n'est pas connecté.\n\n<!-- fin-de-tour: promotion=cluster atlas; dette=0 -->";
    private static final String SOLDE = "C'est rangé.\n\n<!-- fin-de-tour: promotion=aucune; "
            + "promu=cluster atlas -> plateformes.md; dette=0 -->";

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        PromotionReportee reportees = new PromotionReportee();
        GovernanceControl juge = new JugeFinDeTourControl(destinations, reportees);
        GovernanceControl dette = new PromotionDetteBloquanteControl(destinations, reportees);
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true),
                new AtelierCheckpointRunner(List.of(adapt(juge), adapt(dette))));

        when(destinations.pathsForProject(any(), any())).thenReturn(List.of("acces.md", "plateformes.md"));
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
        when(runnerToolGateway.listFiles(eq(runnerTarget), anyString())).thenReturn(ok(""));
        when(runnerToolGateway.readFile(eq(runnerTarget), anyString(), anyString()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
    }

    /** Un contrôle de gouvernance, observé dans la boucle sans monter tout le catalogue. */
    private AtelierCheckpoint adapt(GovernanceControl control) {
        return new AtelierCheckpoint() {
            @Override
            public AtelierCheckpointKind kind() {
                return AtelierCheckpointKind.END_OF_TURN;
            }

            @Override
            public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
                seen.add(context);
                return control.evaluate(context);
            }
        };
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    private void runnerOffline() {
        when(runnerToolGateway.writeFile(eq(runnerTarget), anyString(), anyString(), anyString()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));
    }

    private void runnerOnline() {
        when(runnerToolGateway.writeFile(eq(runnerTarget), anyString(), anyString(), anyString()))
                .thenReturn(ok(""));
    }

    private List<String> userTexts() {
        List<String> texts = new ArrayList<>();
        for (AgentMessage message : agentProvider.lastRequest.messages()) {
            if (!"user".equals(message.role())) {
                continue;
            }
            for (AgentContentBlock block : message.content()) {
                if (block instanceof AgentContentBlock.Text text) {
                    texts.add(text.text());
                }
            }
        }
        return texts;
    }

    @Test
    @DisplayName("runner hors ligne : aucun refus en boucle, la réponse porte la mention unique")
    void offlineRunnerClosesTheTurnWithASingleNotice() {
        runnerOffline();
        agentProvider.enqueueToolCall("write_file", "path", "plateformes.md", "content", "cluster atlas");
        agentProvider.enqueueFinal(PROMOTION_DUE);
        agentProvider.enqueueFinal("ne devrait jamais être demandé");

        AtelierChatResult result = service.chat(userId, workspaceId, "range le cluster dans la carte");

        // Le fournisseur n'est pas rappelé : zéro refus de fin de tour.
        assertThat(agentProvider.remaining()).isEqualTo(1);
        assertThat(seen).hasSize(2);
        assertThat(seen).allSatisfy(context ->
                assertThat(context.machine()).isEqualTo(AtelierMachineReach.OFFLINE));
        assertThat(result.reply()).startsWith("Je ne peux pas écrire").endsWith(PromotionReportee.NOTICE_SENTENCE);
        assertThat(result.reply().split(java.util.regex.Pattern.quote(PromotionReportee.NOTICE), -1))
                .hasSize(2);
    }

    @Test
    @DisplayName("runner revenu : la dette reportée est réclamée au premier tour où il répond")
    void backOnlineTheDeferredDebtIsClaimed() {
        runnerOffline();
        agentProvider.enqueueToolCall("write_file", "path", "plateformes.md", "content", "cluster atlas");
        agentProvider.enqueueFinal(PROMOTION_DUE);
        service.chat(userId, workspaceId, "range le cluster dans la carte");

        runnerOnline();
        agentProvider.reset();
        agentProvider.enqueueToolCall("write_file", "path", "notes.md", "content", "autre sujet");
        agentProvider.enqueueFinal("Fait.\n\n<!-- fin-de-tour: promotion=aucune; dette=0 -->");
        agentProvider.enqueueToolCall("write_file", "path", "plateformes.md", "content", "cluster atlas");
        agentProvider.enqueueFinal(SOLDE);

        AtelierChatResult result = service.chat(userId, workspaceId, "autre chose");

        assertThat(userTexts()).anySatisfy(text -> assertThat(text)
                .startsWith("Fin de tour contrôlée : le poste était hors ligne")
                .contains("cluster atlas"));
        assertThat(result.reply()).isEqualTo(SOLDE);
        assertThat(result.reply()).doesNotContain(PromotionReportee.NOTICE);
    }

    @Test
    @DisplayName("runner revenu en cours de tour : le dernier appel fait foi, les règles ordinaires s'appliquent")
    void runnerBackDuringTheTurnKeepsTheRules() {
        when(runnerToolGateway.writeFile(eq(runnerTarget), anyString(), anyString(), anyString()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE))
                .thenReturn(ok(""));
        agentProvider.enqueueToolCall("write_file", "path", "a.md", "content", "x");
        agentProvider.enqueueToolCall("write_file", "path", "a.md", "content", "x");
        agentProvider.enqueueFinal("Fait.\n\n<!-- fin-de-tour: promotion=cluster atlas; dette=0 -->");
        agentProvider.enqueueFinal(SOLDE);

        AtelierChatResult result = service.chat(userId, workspaceId, "travaille");

        assertThat(seen.get(0).machine()).isEqualTo(AtelierMachineReach.REACHED);
        assertThat(userTexts()).anySatisfy(text -> assertThat(text)
                .startsWith("Fin de tour contrôlée : range d'abord"));
        assertThat(result.reply()).isEqualTo(SOLDE);
    }

    @Test
    @DisplayName("la mention n'est ajoutée qu'une fois")
    void theNoticeIsAppendedOnce() {
        String once = AtelierChatService.appendNotice("Réponse.", PromotionReportee.NOTICE);

        assertThat(once).isEqualTo("Réponse.\n\n" + PromotionReportee.NOTICE_SENTENCE);
        assertThat(AtelierChatService.appendNotice(once, PromotionReportee.NOTICE)).isEqualTo(once);
        assertThat(AtelierChatService.appendNotice("", PromotionReportee.NOTICE))
                .isEqualTo(PromotionReportee.NOTICE_SENTENCE);
        assertThat(AtelierChatService.appendNotice("Réponse.", null)).isEqualTo("Réponse.");
    }
}
