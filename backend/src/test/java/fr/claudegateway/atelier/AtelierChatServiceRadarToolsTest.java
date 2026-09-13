package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.radar.RadarEvidenceSource;
import fr.claudegateway.radar.RadarNote;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarToolCatalog;
import fr.claudegateway.radar.RadarToolExecutor;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.runner.host.ClientSpace;
import fr.claudegateway.runner.host.HostSpaceService;
import fr.claudegateway.teams.TeamsAccessService;
import fr.claudegateway.teams.TeamsToolCatalog;

/**
 * <b>Les outils Radar entrent et sortent de la panoplie de l'agent</b> (F-104 / SF-104-01) : la garde
 * dans {@code buildTools}, le second verrou dans la boucle, et la preuve — le message du tour.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceRadarToolsTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private RunnerCallDispatcher runnerCallDispatcher;
    @Mock private RunnerConfirmationGate confirmationGate;
    @Mock private RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private TeamsAccessService teamsAccess;
    @Mock private HostSpaceService spaces;
    @Mock private RadarToolExecutor executor;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true),
                AtelierCheckpointRunner.none(), ProjectRulesSource.NONE, TeamsToolCatalog.none(), null,
                new RadarToolCatalog(teamsAccess, spaces), executor);

        when(teamsAccess.hasAccess(userId)).thenReturn(true);
        when(spaces.isActive(userId, hostId, ClientSpace.VIGIE)).thenReturn(true);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(java.util.Optional.empty());
        when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId)).thenReturn(List.of());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId("USER".equals(saved.getRole()) ? messageId : UUID.randomUUID());
            }
            return saved;
        });
        when(executor.execute(any(), any(), any(), any()))
                .thenReturn(new RadarToolExecutor.Outcome("{\"subjects\":[]}", false, List.of(), null));
    }

    private Workspace terminal(boolean teams, UUID host) {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        workspace.setTeamsTerminal(teams);
        workspace.setHostId(host);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        return workspace;
    }

    private List<String> toolNames(Workspace workspace) {
        return service.buildTools(userId, workspace).stream().map(AgentTool::name).toList();
    }

    private static AtelierProgressListener silent() {
        return new AtelierProgressListener() {
            @Override
            public void onAction(AtelierStepEvent step) {
                // rien
            }

            @Override
            public void onText(String text) {
                // rien
            }
        };
    }

    @Test
    @DisplayName("terminal Teams d'un client suivi par la Vigie + droit : les six outils radar_* sont donnés")
    void radarToolsJoinTheBelt() {
        assertThat(toolNames(terminal(true, hostId))).containsAll(RadarToolCatalog.CATALOG);
    }

    @Test
    @DisplayName("terminal de projet, sans droit, hors Vigie, sans poste : aucun outil radar_*")
    void closedWhenOneConditionIsMissing() {
        assertThat(toolNames(terminal(false, hostId))).noneMatch(RadarToolCatalog::isRadarTool);
        assertThat(toolNames(terminal(true, null))).noneMatch(RadarToolCatalog::isRadarTool);

        when(spaces.isActive(userId, hostId, ClientSpace.VIGIE)).thenReturn(false);
        assertThat(toolNames(terminal(true, hostId))).noneMatch(RadarToolCatalog::isRadarTool);

        when(spaces.isActive(userId, hostId, ClientSpace.VIGIE)).thenReturn(true);
        when(teamsAccess.hasAccess(userId)).thenReturn(false);
        assertThat(toolNames(terminal(true, hostId))).noneMatch(RadarToolCatalog::isRadarTool);
    }

    @Test
    @DisplayName("un appel radar_* passe par l'exécuteur, au périmètre du poste, avec le message du tour pour preuve")
    void aRadarCallRunsInTheTerminalScopeWithTheMessageAsEvidence() {
        terminal(true, hostId);
        agentProvider.enqueueToolCallWithObject(RadarToolCatalog.FIND_SUBJECT, "{\"query\":\"MFA\"}");
        agentProvider.enqueueFinal("Le MFA avance.");

        service.chatStreaming(userId, workspaceId, "où en est le MFA ?", silent());

        ArgumentCaptor<RadarNote> note = ArgumentCaptor.forClass(RadarNote.class);
        verify(executor).execute(eq(new RadarScope(userId, hostId)), eq(RadarToolCatalog.FIND_SUBJECT), any(),
                note.capture());
        assertThat(note.getValue().source()).isEqualTo(RadarEvidenceSource.USER_NOTE);
        assertThat(note.getValue().sourceRef()).isEqualTo("atelier-message:" + messageId);
        assertThat(note.getValue().quote()).isEqualTo("où en est le MFA ?");
    }

    @Test
    @DisplayName("SECOND VERROU : hors garde, un appel radar_* forcé est refusé sans rien exécuter")
    void aForcedCallOutsideTheGuardIsRefused() {
        terminal(false, hostId);
        agentProvider.enqueueToolCallWithObject(RadarToolCatalog.CLOSE_SUBJECT,
                "{\"subject_id\":\"" + UUID.randomUUID() + "\"}");
        agentProvider.enqueueFinal("Je ne peux pas.");

        service.chatStreaming(userId, workspaceId, "le sujet LDAP est clos", silent());

        verify(executor, never()).execute(any(), any(), any(), any());
    }

    // ------------------------------------------------------------ F-104 / SF-104-03 : le Radar au terminal

    @Test
    @DisplayName("SF-104-03 — garde ouverte : la consigne porte le bloc Radar ; fermée : non")
    void systemPromptCarriesTheRadarNoticeUnderTheGuard() {
        assertThat(service.buildSystemPrompt(userId, terminal(true, hostId)))
                .contains(RadarToolCatalog.TERMINAL_NOTICE);
        assertThat(service.buildSystemPrompt(userId, terminal(false, hostId))).doesNotContain("--- Radar du client ---");

        when(spaces.isActive(userId, hostId, ClientSpace.VIGIE)).thenReturn(false);
        assertThat(service.buildSystemPrompt(userId, terminal(true, hostId))).doesNotContain("--- Radar du client ---");

        when(spaces.isActive(userId, hostId, ClientSpace.VIGIE)).thenReturn(true);
        when(teamsAccess.hasAccess(userId)).thenReturn(false);
        assertThat(service.buildSystemPrompt(userId, terminal(true, hostId))).doesNotContain("--- Radar du client ---");
    }

    @Test
    @DisplayName("SF-104-03 — l'étape Radar est relayée avec sa cible lisible, sans identifiant")
    void theRadarStepIsRelayedInPlainWords() {
        terminal(true, hostId);
        agentProvider.enqueueToolCallWithObject(RadarToolCatalog.FIND_SUBJECT, "{\"query\":\"MFA\"}");
        agentProvider.enqueueFinal("Le MFA avance.");
        List<AtelierProgressListener.AtelierStepEvent> steps = new java.util.ArrayList<>();

        service.chatStreaming(userId, workspaceId, "où en est le MFA ?", new AtelierProgressListener() {
            @Override
            public void onAction(AtelierStepEvent step) {
                steps.add(step);
            }

            @Override
            public void onText(String text) {
                // rien
            }
        });

        assertThat(steps).extracting(AtelierProgressListener.AtelierStepEvent::path)
                .containsExactly("Radar · recherche « MFA »");
        assertThat(steps).extracting(AtelierProgressListener.AtelierStepEvent::type)
                .containsExactly(RadarToolCatalog.FIND_SUBJECT);
    }
}
