package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * F-149 / SF-149-03 — politique de modèle : la <b>sous-boucle d'exploration</b> tourne sur le modèle
 * d'exploration (Sonnet en prod), là où se concentre la lecture lourde déléguée (SF-149-02), tandis que
 * la <b>boucle principale</b> reste sur le modèle principal (Opus). <b>Repli sûr</b> : sans réglage, la
 * sous-boucle suit le modèle principal.
 *
 * <p>Le provider de test distingue la sous-boucle de la boucle principale par la consigne système
 * ({@code "Tu explores…"}, {@link AtelierExploration}) et <b>enregistre le modèle</b> reçu à chaque
 * appel — c'est {@code AgentTurnRequest.model()} qui prouve quel modèle a réellement servi. Aucun
 * couplage direct à un modèle : le modèle voyage comme une chaîne via {@link AiAgentProvider}.</p>
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceExploreModelTest {

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

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private ModelRecordingProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ModelRecordingProvider();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(quotaService.currentUsage(userId)).thenReturn(
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

    private AtelierChatService serviceWith(AtelierProperties properties) {
        return new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) provider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService, properties);
    }

    /** Forme canonique (24 composants) avec le modèle d'exploration réglé ; storageExecution=true (13ᵉ). */
    private static AtelierProperties withExploreModel(String exploreModel) {
        return new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, true, null, null, null, null, null, null, null, null, null, null,
                exploreModel);
    }

    /** Forme de compatibilité (13 composants) : exploreModel absent (null) ; storageExecution=true. */
    private static AtelierProperties withoutExploreModel() {
        return new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, true);
    }

    @Test
    void theExploreSubLoopRunsOnTheConfiguredModelWhileTheMainLoopKeepsOpus() {
        AtelierChatService service = serviceWith(withExploreModel("claude-sonnet-5"));

        service.chat(userId, workspaceId, "audite le dépôt");

        // La sous-boucle a bien tourné sur le modèle d'exploration configuré (Sonnet)…
        assertThat(provider.subLoopModel).isEqualTo("claude-sonnet-5");
        // …et la boucle principale est restée sur le modèle principal (Opus, défaut du harnais).
        assertThat(provider.mainModel).isEqualTo(AtelierProperties.DEFAULT_MODEL);
        assertThat(provider.mainModel).isEqualTo("claude-opus-5");
    }

    @Test
    void withoutConfigurationTheSubLoopFallsBackToTheMainModel() {
        AtelierChatService service = serviceWith(withoutExploreModel());

        service.chat(userId, workspaceId, "audite le dépôt");

        // Repli sûr : pas de modèle d'exploration => la sous-boucle suit le modèle principal.
        assertThat(provider.subLoopModel).isEqualTo("claude-opus-5");
        assertThat(provider.mainModel).isEqualTo("claude-opus-5");
        assertThat(provider.subLoopModel).isEqualTo(provider.mainModel);
    }

    /**
     * Provider scripté minimal : la boucle principale émet un {@code explore} au premier tour puis
     * conclut ; la sous-boucle conclut d'emblée. Chaque appel enregistre le modèle reçu.
     */
    private static final class ModelRecordingProvider implements AiAgentProvider {

        private final ObjectMapper mapper = new ObjectMapper();
        private int mainCalls = 0;
        volatile String mainModel;
        volatile String subLoopModel;

        @Override
        public AgentTurn nextTurn(AgentTurnRequest request) {
            String system = request.system();
            boolean subLoop = system != null && system.startsWith("Tu explores");
            if (subLoop) {
                subLoopModel = request.model();
                return new AgentTurn("Conclusion de la sous-boucle.", List.of(), true, 5, 5);
            }
            mainModel = request.model();
            if (mainCalls++ == 0) {
                ObjectNode input = mapper.createObjectNode();
                input.put("question", "audite le dépôt");
                List<AgentToolCall> calls = new ArrayList<>();
                calls.add(new AgentToolCall("explore-0", "explore", input));
                return new AgentTurn("", calls, false, 5, 5);
            }
            return new AgentTurn("done", List.of(), true, 5, 5);
        }
    }
}
