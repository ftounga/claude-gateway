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
 * <b>Compacter maintenant</b> (F-162 / SF-162-04) : la compaction <b>douce</b> à la demande. On vérifie
 * qu'elle résume les vieux tours <b>en gardant le résumé</b> (à la différence du « Nouveau départ » qui
 * l'efface), qu'elle rend le nombre de tours résumés, et qu'elle est best-effort (rien à compacter ou
 * synthèse blanche ⇒ résultat neutre, jamais d'erreur).
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceManualCompactionTest {

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
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private Workspace workspace;
    private final List<AtelierMessage> history = new ArrayList<>();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));
        // Compaction branchée : keepRecentTurns = 2, seuil bas (sans effet ici car compactNow ne le lit pas).
        AtelierCompactionService compaction = new AtelierCompactionService(messageRepository,
                workspaceRepository, (AiAgentProvider) agentProvider,
                new AtelierCompactionProperties(true, 100, 2),
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));
        service.setCompactionService(compaction);

        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        lenient().when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        lenient().when(workspaceRepository.save(any(Workspace.class))).thenAnswer(i -> i.getArgument(0));
    }

    private AtelierMessage msg(String role, String content, OffsetDateTime at) {
        return AtelierMessage.builder().id(UUID.randomUUID()).workspaceId(workspaceId).userId(userId)
                .role(role).content(content).createdAt(at).build();
    }

    private String longText(String prefix) {
        return prefix + " " + "x".repeat(400);
    }

    /** Trois échanges : keepRecentTurns=2 garde les 2 derniers messages, résume les 4 premiers (2 tours USER). */
    private void threeExchanges() {
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(6);
        for (int i = 0; i < 3; i++) {
            history.add(msg("USER", longText("demande " + i), t0.plusMinutes(i * 2L)));
            history.add(msg("ASSISTANT", longText("réponse " + i), t0.plusMinutes(i * 2L + 1)));
        }
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);
        // Nombre de tours USER antérieurs à la fenêtre (aucun ici : la fenêtre est tout le fil).
        lenient().when(messageRepository.countByWorkspaceIdAndUserIdAndRoleAndCreatedAtLessThan(
                any(), any(), any(), any())).thenReturn(0L);
    }

    @Test
    void manualCompactionSummarizesOldTurnsAndKeepsTheSummary() {
        threeExchanges();
        agentProvider.enqueueFinal("Résumé compact du travail passé.");

        AtelierChatService.AtelierCompactResult result = service.compactManually(userId, workspaceId);

        assertThat(result.compacted()).isTrue();
        // 4 anciens messages résumés = 2 tours USER.
        assertThat(result.summarizedTurns()).isEqualTo(2);
        // La compaction DOUCE conserve le résumé (distinction avec « Nouveau départ » qui l'efface).
        assertThat(workspace.getChatThreadSummary()).isEqualTo("Résumé compact du travail passé.");
        assertThat(workspace.getChatThreadStartedAt()).isNotNull();
    }

    @Test
    void nothingToCompactReturnsNeutralResultWithoutError() {
        OffsetDateTime t0 = OffsetDateTime.now().minusMinutes(5);
        history.add(msg("USER", "petite demande", t0));
        history.add(msg("ASSISTANT", "petite réponse", t0.plusMinutes(1)));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);

        AtelierChatService.AtelierCompactResult result = service.compactManually(userId, workspaceId);

        // Fil déjà court (splitIndex == 0) : rien n'est résumé, ce n'est pas une erreur.
        assertThat(result.compacted()).isFalse();
        assertThat(result.summarizedTurns()).isZero();
        assertThat(workspace.getChatThreadSummary()).isNull();
    }

    @Test
    void bestEffortWhenSynthesisIsBlankLeavesThreadIntact() {
        threeExchanges();
        agentProvider.enqueueEmptyFinal(); // résumé blanc : rien n'est écrit

        AtelierChatService.AtelierCompactResult result = service.compactManually(userId, workspaceId);

        assertThat(result.compacted()).isFalse();
        assertThat(result.summarizedTurns()).isZero();
        assertThat(workspace.getChatThreadSummary()).isNull();
    }

    @Test
    void compactionServiceNotWiredReturnsNeutralResult() {
        service.setCompactionService(null);

        AtelierChatService.AtelierCompactResult result = service.compactManually(userId, workspaceId);

        assertThat(result.compacted()).isFalse();
        assertThat(result.summarizedTurns()).isZero();
    }
}
