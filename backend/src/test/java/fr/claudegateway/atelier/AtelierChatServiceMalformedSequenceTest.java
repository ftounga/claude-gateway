package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * Repli sur séquence malformée (F-117 / SF-117-08) : un 400 « invalid_request » structurel simulé ne
 * tue plus le tour — la boucle réassainit (rebuild) puis relance une fois ; si l'échec persiste, un
 * message clair, et jamais la cascade d'un USER orphelin empilé.
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceMalformedSequenceTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private WorkspaceRepository workspaceRepository;
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

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private Workspace workspace;
    private final List<AtelierMessage> history = new ArrayList<>();
    private final List<AtelierMessage> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        lenient().when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(UUID.randomUUID());
            }
            saved.add(message);
            return message;
        });
        lenient().when(workspaceService.tree(any(), any())).thenReturn(List.of());
    }

    private AtelierChatService service() {
        return new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));
    }

    private AtelierMessage msg(String role, String content, OffsetDateTime at) {
        return AtelierMessage.builder().id(UUID.randomUUID()).workspaceId(workspaceId).userId(userId)
                .role(role).content(content).createdAt(at).build();
    }

    private long savedUserCount() {
        return saved.stream().filter(m -> "USER".equalsIgnoreCase(m.getRole())).count();
    }

    @Test
    void aMalformedSequenceIsRepairedThenRelaunched() {
        OffsetDateTime t0 = OffsetDateTime.now().minusMinutes(10);
        history.add(msg("USER", "demande", t0));
        history.add(msg("ASSISTANT", "réponse", t0.plusMinutes(1)));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);

        AtelierChatService service = service();
        // Le 1er appel échoue en séquence malformée ; le rebuild + relance aboutissent.
        agentProvider.enqueueMalformedRequest(1);
        agentProvider.enqueueFinal("Voilà la réponse.");

        AtelierChatService.AtelierChatResult result = service.chat(userId, workspaceId, "continue");

        // Pas d'échec dur : l'utilisateur reçoit sa réponse.
        assertThat(result.reply()).isEqualTo("Voilà la réponse.");
        // Deux appels fournisseur : la tentative malformée, puis la relance après réassainissement.
        assertThat(agentProvider.messageSnapshots).hasSize(2);
        // Aucun USER orphelin empilé : un seul message utilisateur persisté (la consigne du tour).
        assertThat(savedUserCount()).isEqualTo(1);
    }

    @Test
    void aPersistentMalformedSequenceRendersAClearMessageWithoutCascade() {
        OffsetDateTime t0 = OffsetDateTime.now().minusMinutes(10);
        history.add(msg("USER", "demande", t0));
        history.add(msg("ASSISTANT", "réponse", t0.plusMinutes(1)));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);

        AtelierChatService service = service();
        // Malformé au 1er appel ET à la relance : le filet est borné à une réparation par message.
        agentProvider.enqueueMalformedRequest(2);

        AtelierChatService.AtelierChatResult result = service.chat(userId, workspaceId, "continue");

        // Message clair, jamais un provider_error sans issue.
        assertThat(result.reply()).isEqualTo(AtelierChatService.MALFORMED_SEQUENCE_REPLY);
        // Deux appels : la tentative et l'unique relance.
        assertThat(agentProvider.messageSnapshots).hasSize(2);
        // Anti-cascade : le tour échoué n'a empilé aucun USER supplémentaire (un seul persisté).
        assertThat(savedUserCount()).isEqualTo(1);
    }
}
