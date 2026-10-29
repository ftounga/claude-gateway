package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.atelier.journey.JourneyMode;
import fr.claudegateway.atelier.journey.JourneyPhase;
import fr.claudegateway.atelier.journey.JourneyToolCatalog;
import fr.claudegateway.atelier.journey.JourneyToolExecutor;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyService;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.pages.PageToolCatalog;
import fr.claudegateway.pages.PageToolExecutor;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.radar.RadarToolCatalog;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.teams.TeamsToolCatalog;

/**
 * <b>Le parcours du sujet dans la boucle</b> (F-176) : la porte tenue par le harnais (SF-176-04), le
 * parcours joint au MESSAGE du tour et jamais au système, et Libre inchangé (Q4).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceJourneyTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private RunnerCallDispatcher runnerCallDispatcher;
    @Mock private RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private SpaceEntitlementService entitlements;
    @Mock private PageToolExecutor pageExecutor;
    @Mock private SubjectJourneyService journeyService;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final AtelierProgressListener listener = new AtelierProgressListener() {
        @Override
        public void onAction(AtelierStepEvent step) {
        }

        @Override
        public void onText(String text) {
        }
    };

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, new RunnerConfirmationGate(200L, 200L), runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true),
                AtelierCheckpointRunner.none(), ProjectRulesSource.NONE, TeamsToolCatalog.none(), null,
                RadarToolCatalog.none(), null, new PageToolCatalog(entitlements), pageExecutor);
        service.setJourneyService(journeyService);
        service.setJourneyTools(new JourneyToolCatalog(entitlements), new JourneyToolExecutor(journeyService));

        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId)).thenReturn(List.of());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(UUID.randomUUID());
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.LOCAL);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
    }

    private void journey(JourneyMode mode, JourneyPhase phase, Integer validated, int version) {
        when(journeyService.forTurn(userId, workspaceId)).thenReturn(SubjectJourney.builder()
                .userId(userId).workspaceId(workspaceId).mode(mode).phase(phase)
                .validatedVersion(validated).planVersion(version).build());
    }

    @Test
    @DisplayName("SF-176-04 : en Guidé · Investigation, un kubectl apply est refusé AVANT toute émission")
    void gateBlocksBeforeEmission() {
        journey(JourneyMode.GUIDE, JourneyPhase.INVESTIGATION, null, 0);
        agentProvider.enqueueToolCall("bash", "command", "kubectl apply -f ingress.yaml");
        agentProvider.enqueueFinal("J'attends.");

        service.chatStreaming(userId, workspaceId, "corrige l'ingress", listener);

        verify(runnerToolGateway, never()).bash(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        assertThat(agentProvider.messageSnapshots.get(1)).contains("PORTE DU PARCOURS GUIDÉ");
        verify(journeyService).recordGateBlocked(any(), eq(fr.claudegateway.atelier.journey.JourneyPlan.Risk.EXTERNE),
                eq("bash"));
    }

    @Test
    @DisplayName("SF-176-07 : un refus dû à un programme inconnu est marqué « inconnu », jamais la commande")
    void unknownProgramIsMeasured() {
        journey(JourneyMode.GUIDE, JourneyPhase.PLAN, null, 1);
        agentProvider.enqueueToolCall("bash", "command", "outil-maison --secret=abc");
        agentProvider.enqueueFinal("J'attends.");

        service.chatStreaming(userId, workspaceId, "fais", listener);

        assertThat(agentProvider.messageSnapshots.get(1))
                .contains("Ce terminal est en mode Guidé, phase Plan : cette action attend la validation du plan.");
        verify(journeyService).recordGateBlocked(any(),
                eq(fr.claudegateway.atelier.journey.JourneyPlan.Risk.REVERSIBLE), eq("bash · inconnu"));
    }

    @Test
    @DisplayName("SF-176-07 : un sujet clos est revenu en Libre — aws sso login et terraform apply passent")
    void closedSubjectHasNoGate() {
        journey(JourneyMode.LIBRE, JourneyPhase.CLOS, 1, 1);
        agentProvider.enqueueToolCall("bash", "command", "terraform apply -auto-approve");
        agentProvider.enqueueFinal("Fait.");

        service.chatStreaming(userId, workspaceId, "applique", listener);

        assertThat(agentProvider.messageSnapshots.get(1)).doesNotContain("PORTE DU PARCOURS GUIDÉ");
        verify(journeyService, never()).recordGateBlocked(any(), any(), any());
    }

    @Test
    @DisplayName("SF-176-04 : en Libre, la porte n'existe pas (Q4) — rien n'est refusé ni journalisé")
    void libreHasNoGate() {
        journey(JourneyMode.LIBRE, null, null, 0);
        agentProvider.enqueueToolCall("bash", "command", "kubectl apply -f ingress.yaml");
        agentProvider.enqueueFinal("Fait.");

        service.chatStreaming(userId, workspaceId, "corrige l'ingress", listener);

        assertThat(agentProvider.messageSnapshots.get(1)).doesNotContain("PORTE DU PARCOURS GUIDÉ");
        verify(journeyService, never()).recordGateBlocked(any(), any(), any());
    }

    @Test
    @DisplayName("SF-176-04 : en Guidé, la lecture reste libre — le parcours n'est même pas consulté pour elle")
    void readIsFree() {
        journey(JourneyMode.GUIDE, JourneyPhase.INVESTIGATION, null, 0);
        agentProvider.enqueueToolCall("bash", "command", "kubectl get pods -n ingress");
        agentProvider.enqueueFinal("Lu.");

        service.chatStreaming(userId, workspaceId, "regarde", listener);

        assertThat(agentProvider.messageSnapshots.get(1)).doesNotContain("PORTE DU PARCOURS GUIDÉ");
        verify(journeyService, never()).recordGateBlocked(any(), any(), any());
    }

    @Test
    @DisplayName("SF-176-04 : en Exécution sur le plan validé, la modification passe")
    void validatedPlanOpensTheGate() {
        journey(JourneyMode.GUIDE, JourneyPhase.EXECUTION, 2, 2);
        agentProvider.enqueueToolCall("bash", "command", "kubectl apply -f ingress.yaml");
        agentProvider.enqueueFinal("Appliqué.");

        service.chatStreaming(userId, workspaceId, "applique", listener);

        assertThat(agentProvider.messageSnapshots.get(1)).doesNotContain("PORTE DU PARCOURS GUIDÉ");
    }

    @Test
    @DisplayName("F-176 : le parcours part dans le MESSAGE du tour, jamais dans le système ; le guide est stable")
    void journeyGoesInTheMessage() {
        journey(JourneyMode.GUIDE, JourneyPhase.PLAN, null, 1);
        agentProvider.enqueueFinal("ok");

        service.chatStreaming(userId, workspaceId, "on en est où ?", listener);

        assertThat(agentProvider.messageSnapshots.get(0)).contains("Mode GUIDÉ · phase : Plan");
        assertThat(agentProvider.lastRequest.system()).doesNotContain("Mode GUIDÉ · phase")
                .contains("Parcours du sujet (Libre / Guidé)");
        assertThat(agentProvider.toolNamesSeen).contains(JourneyToolCatalog.PROPOSE_GUIDED, JourneyToolCatalog.SET_PLAN);
    }

    @Test
    @DisplayName("SF-208-02 : un outil du parcours appliqué émet « journey » pendant le tour ; un refus n'émet rien")
    void journeyEventAfterSuccessfulTool() {
        journey(JourneyMode.GUIDE, JourneyPhase.EXECUTION, 1, 1);
        when(journeyService.reopenInvestigation(eq(userId), eq(workspaceId), eq(2), eq("502 persiste")))
                .thenReturn(true);
        when(journeyService.reopenInvestigation(eq(userId), eq(workspaceId), eq(null), eq("rien")))
                .thenReturn(false);
        List<String> events = new java.util.ArrayList<>();
        AtelierProgressListener recording = new AtelierProgressListener() {
            @Override
            public void onAction(AtelierStepEvent step) {
            }

            @Override
            public void onText(String text) {
            }

            @Override
            public void onJourney(String tool, Integer chantier) {
                events.add(tool + "@" + chantier);
            }
        };
        agentProvider.enqueueToolCall(JourneyToolCatalog.REOPEN, "reason", "502 persiste", "chantier", "2");
        agentProvider.enqueueToolCall(JourneyToolCatalog.REOPEN, "reason", "rien");
        agentProvider.enqueueFinal("Retour en investigation.");

        service.chatStreaming(userId, workspaceId, "ça ne marche pas", recording);

        assertThat(events).containsExactly(JourneyToolCatalog.REOPEN + "@2");
    }

    private static final String CAP_BLOCK = "Fait.\n<<suites>>\n["
            + "{\"titre\":\"Rétention KMS\",\"pourquoi\":\"vu\",\"etiquette\":\"Risque\",\"demande\":\"KMS.\",\"etape\":null},"
            + "{\"titre\":\"Export SIEM\",\"pourquoi\":\"vu\",\"etiquette\":\"Continuer\",\"demande\":\"SIEM.\"},"
            + "{\"titre\":\"Policy NAP\",\"pourquoi\":\"étape 2 à faire\",\"etiquette\":\"Continuer\",\"demande\":\"NAP.\",\"etape\":2}"
            + "]\n<</suites>>";

    @Test
    @DisplayName("SF-196-01 / SF-209-01 : chantier guidé ouvert — l'étape à gauche, le hors-plan à droite, avancement exposé")
    void nextStepsAreAnchoredToTheOpenChantier() {
        when(journeyService.forTurn(userId, workspaceId)).thenReturn(SubjectJourney.builder()
                .userId(userId).workspaceId(workspaceId).mode(JourneyMode.GUIDE).phase(JourneyPhase.EXECUTION)
                .validatedVersion(1).planVersion(1).chantierNumber(1)
                .planJson("[{\"title\":\"Diagnostiquer\",\"status\":\"VERIFIE\"},"
                        + "{\"title\":\"Appliquer la policy NAP V12\",\"status\":\"A_FAIRE\"}]")
                .build());
        agentProvider.enqueueFinal(CAP_BLOCK);

        AtelierChatService.AtelierChatResult result = service.chatStreaming(userId, workspaceId, "suite", listener);

        assertThat(result.nextSteps()).extracting(fr.claudegateway.atelier.nextsteps.NextStep::title)
                .containsExactly("Policy NAP", "Rétention KMS", "Export SIEM");
        assertThat(result.nextSteps()).extracting(fr.claudegateway.atelier.nextsteps.NextStep::column)
                .containsExactly(fr.claudegateway.atelier.nextsteps.NextStep.Column.CHANTIER,
                        fr.claudegateway.atelier.nextsteps.NextStep.Column.HORS,
                        fr.claudegateway.atelier.nextsteps.NextStep.Column.HORS);
        assertThat(result.nextSteps().get(0).stepTitle()).isEqualTo("Appliquer la policy NAP V12");
        assertThat(result.nextSteps().get(1).scope())
                .isEqualTo(fr.claudegateway.atelier.nextsteps.NextStep.Scope.APPROFONDISSEMENT);
        assertThat(result.nextStepsChantier())
                .isEqualTo(new fr.claudegateway.atelier.nextsteps.ChantierAnchor.Progress(1, 1, 2));
        org.mockito.ArgumentCaptor<AtelierMessage> saved = org.mockito.ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        AtelierMessage assistant = saved.getAllValues().stream()
                .filter(m -> "ASSISTANT".equals(m.getRole())).reduce((x, y) -> y).orElseThrow();
        assertThat(assistant.getTerminalJson()).contains("\"nextStepsChantier\"").contains("\"APPROFONDISSEMENT\"");
    }

    @Test
    @DisplayName("SF-196-01 : mode Libre — suites inchangées, aucun rattachement")
    void libreNextStepsAreNotAnchored() {
        journey(JourneyMode.LIBRE, null, null, 0);
        agentProvider.enqueueFinal(CAP_BLOCK);

        AtelierChatService.AtelierChatResult result = service.chatStreaming(userId, workspaceId, "suite", listener);

        assertThat(result.nextSteps()).extracting(fr.claudegateway.atelier.nextsteps.NextStep::title)
                .containsExactly("Rétention KMS", "Export SIEM", "Policy NAP");
        assertThat(result.nextSteps()).allSatisfy(s -> assertThat(s.scope()).isNull());
        assertThat(result.nextStepsChantier()).isNull();
    }

    private SubjectJourney laneEight() {
        return SubjectJourney.builder()
                .userId(userId).workspaceId(workspaceId).mode(JourneyMode.GUIDE).phase(JourneyPhase.EXECUTION)
                .validatedVersion(1).planVersion(1).chantierNumber(8)
                .planJson("[{\"title\":\"Diagnostiquer\",\"status\":\"VERIFIE\"},"
                        + "{\"title\":\"Appliquer la policy NAP V12\",\"status\":\"A_FAIRE\"}]")
                .build();
    }

    @Test
    @DisplayName("SF-221-05 : fil 8 montré en split — note « Écran partagé » au message, système identique, HORS retirées")
    void splitPaneKeepsOnlyItsChantierSuites() {
        when(journeyService.forLane(userId, workspaceId, 8)).thenReturn(laneEight());
        agentProvider.enqueueFinal(CAP_BLOCK);
        agentProvider.enqueueFinal(CAP_BLOCK);

        service.setLaneSplit(userId, workspaceId, 8, false);
        AtelierChatService.AtelierChatResult plain = service.chatStreaming(userId, workspaceId, 8, "suite",
                fr.claudegateway.agent.AgentTurnMode.ACT, listener, false, List.of());
        String systemPlain = agentProvider.lastRequest.system();
        service.setLaneSplit(userId, workspaceId, 8, true);
        AtelierChatService.AtelierChatResult split = service.chatStreaming(userId, workspaceId, 8, "suite",
                fr.claudegateway.agent.AgentTurnMode.ACT, listener, false, List.of());

        assertThat(plain.nextSteps()).extracting(fr.claudegateway.atelier.nextsteps.NextStep::column)
                .contains(fr.claudegateway.atelier.nextsteps.NextStep.Column.HORS);
        assertThat(split.nextSteps()).extracting(fr.claudegateway.atelier.nextsteps.NextStep::title)
                .containsExactly("Policy NAP");
        assertThat(agentProvider.messageSnapshots.get(0)).doesNotContain("Écran partagé");
        assertThat(agentProvider.messageSnapshots.get(1)).contains("[Écran partagé]", "chantier 8");
        assertThat(agentProvider.lastRequest.system()).as("préfixe stable : la consigne système ne bouge pas")
                .isEqualTo(systemPlain);
    }

    @Test
    @DisplayName("SF-221-05 : le fil commun n'est jamais montré en volet — split sans effet")
    void splitIsIgnoredOnTheCommonLane() {
        when(journeyService.forTurn(userId, workspaceId)).thenReturn(laneEight());
        agentProvider.enqueueFinal(CAP_BLOCK);

        service.setLaneSplit(userId, workspaceId, null, true);
        AtelierChatService.AtelierChatResult result = service.chatStreaming(userId, workspaceId, "suite", listener);

        assertThat(result.nextSteps()).extracting(fr.claudegateway.atelier.nextsteps.NextStep::column)
                .contains(fr.claudegateway.atelier.nextsteps.NextStep.Column.HORS);
        assertThat(agentProvider.messageSnapshots.get(0)).doesNotContain("Écran partagé");
    }
}
