package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AgentTurnMode;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * F-245 / SF-245-01 (critère 6) — la consigne système d'un tour dans le <b>fil Jarvis</b> est <b>identique à
 * l'octet</b> à celle d'un sujet hébergé : préfixe stable, cache intact (cadrage §3). Même chose pour la panoplie
 * d'outils, fixe pour ce type d'espace.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceJarvisThreadPromptTest {

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

    private Workspace hosted(UUID id, boolean jarvisThread) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setUserId(userId);
        workspace.setName(jarvisThread ? "Jarvis" : "Doc archi");
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        if (jarvisThread) {
            workspace.setJarvisThreadUserId(userId);
        }
        when(workspaceService.tree(userId, id)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, id, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));
        return workspace;
    }

    @Test
    void jarvisThreadSystemPromptIsByteIdenticalToAHostedSubject() {
        Workspace subject = hosted(workspaceId, false);
        Workspace thread = hosted(UUID.randomUUID(), true);
        assertThat(thread.isJarvisThread()).isTrue();

        for (AgentTurnMode mode : AgentTurnMode.values()) {
            assertThat(service.buildSystemPrompt(userId, thread, mode))
                    .isEqualTo(service.buildSystemPrompt(userId, subject, mode));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void f245TheUniverseGoesInTheMessageOfAJarvisThreadTurnOnly() {
        Workspace subject = hosted(workspaceId, false);
        Workspace thread = hosted(UUID.randomUUID(), true);
        fr.claudegateway.jarvis.fastbrain.JarvisFastContextBuilder builder =
                org.mockito.Mockito.mock(fr.claudegateway.jarvis.fastbrain.JarvisFastContextBuilder.class);
        when(builder.reference(userId)).thenReturn("Client CAGIP\n- Sujet Helios");
        org.springframework.beans.factory.ObjectProvider<fr.claudegateway.jarvis.fastbrain.JarvisFastContextBuilder>
                provider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(builder);
        String systemBefore = service.buildSystemPrompt(userId, thread, AgentTurnMode.ACT);
        service.setJarvisContext(provider);

        String note = service.withJarvisUniverse(userId, thread, "Bonjour");
        assertThat(note).startsWith(AtelierChatService.JARVIS_UNIVERSE_HEADER).contains("Sujet Helios")
                .endsWith("Bonjour");
        // Ailleurs : inchangé à l'octet près.
        assertThat(service.withJarvisUniverse(userId, subject, "Bonjour")).isEqualTo("Bonjour");
        // Critère 5 : l'univers n'est jamais dans la consigne système.
        assertThat(service.buildSystemPrompt(userId, thread, AgentTurnMode.ACT)).isEqualTo(systemBefore)
                .doesNotContain("Sujet Helios");
    }

    @Test
    void jarvisThreadToolBeltIsTheHostedOne() {
        Workspace subject = hosted(workspaceId, false);
        Workspace thread = hosted(UUID.randomUUID(), true);

        assertThat(service.buildTools(userId, thread, AgentTurnMode.ACT).stream().map(AgentTool::name).toList())
                .isEqualTo(service.buildTools(userId, subject, AgentTurnMode.ACT).stream().map(AgentTool::name)
                        .toList());
    }
}
