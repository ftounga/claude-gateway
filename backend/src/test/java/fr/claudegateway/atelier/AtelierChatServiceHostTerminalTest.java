package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
import org.springframework.data.domain.Pageable;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.radar.RadarToolCatalog;
import fr.claudegateway.radar.RadarToolExecutor;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.runner.host.HostSpaceService;
import fr.claudegateway.teams.TeamsAccessService;
import fr.claudegateway.teams.TeamsToolCatalog;

/**
 * <b>Le terminal du poste se souvient de tout le poste</b> (F-178 / SF-178-01) : {@code recall} y porte
 * un paramètre {@code portee} ; en portée « poste », la recherche couvre le terminal du poste et les sujets
 * de CE poste, du MÊME utilisateur, et chaque extrait est étiqueté {@code [sujet · date · rôle]}. Dans un
 * sujet, le paramètre n'existe pas et un appel qui l'enverrait reste borné au fil.
 *
 * <p>Et ses lectures (SF-178-02+) : {@code sujets_etat} n'est déclaré et exécuté qu'au terminal du poste.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceHostTerminalTest {

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
    @Mock private fr.claudegateway.atelier.recall.AtelierSemanticRecall semanticRecall;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID subjectA = UUID.randomUUID();
    private final UUID subjectB = UUID.randomUUID();

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
        service.setSemanticRecall(semanticRecall);

        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(java.util.Optional.empty());
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
    }

    private Workspace hostTerminal() {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setName("racine");
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        workspace.setHostId(hostId);
        workspace.setHostTerminal(true);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(workspaceService.listByHost(userId, hostId)).thenReturn(List.of(
                subject(subjectA, "data-platform", userId, hostId),
                subject(subjectB, "lzi", userId, hostId),
                // Défense en profondeur : un sujet d'un AUTRE utilisateur ou d'un AUTRE poste, s'il
                // remontait par erreur, n'entre jamais dans la portée.
                subject(UUID.randomUUID(), "intrus-autre-user", UUID.randomUUID(), hostId),
                subject(UUID.randomUUID(), "intrus-autre-poste", userId, UUID.randomUUID())));
        return workspace;
    }

    private Workspace plainSubject() {
        Workspace workspace = subject(workspaceId, "data-platform", userId, hostId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        return workspace;
    }

    private static Workspace subject(UUID id, String name, UUID owner, UUID host) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setUserId(owner);
        workspace.setName(name);
        workspace.setHostId(host);
        return workspace;
    }

    private AtelierMessage message(UUID ws, String role, String content, String when) {
        return AtelierMessage.builder().id(UUID.randomUUID())
                .workspaceId(ws).userId(userId).role(role).content(content)
                .createdAt(OffsetDateTime.parse(when)).build();
    }

    /** Listener capteur : garde le repère de rappel émis. */
    private static final class Captor implements AtelierProgressListener {
        final List<String> recalled = new java.util.ArrayList<>();

        @Override
        public void onAction(AtelierStepEvent step) {
            // rien
        }

        @Override
        public void onText(String text) {
            // rien
        }

        @Override
        public void onRecalled(String repere) {
            recalled.add(repere);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recallProperties(Workspace workspace) {
        AgentTool recall = service.buildTools(userId, workspace).stream()
                .filter(tool -> "recall".equals(tool.name())).findFirst().orElseThrow();
        return (Map<String, Object>) recall.inputSchema().get("properties");
    }

    @Test
    @DisplayName("au terminal du poste, recall déclare `portee` (fil|poste) ; dans un sujet, non")
    void scopeParameterOnlyAtHostTerminal() {
        assertThat(recallProperties(hostTerminal())).containsKeys("query", "portee");
        assertThat(recallProperties(plainSubject())).containsOnlyKeys("query");
    }

    @Test
    @DisplayName("portée poste : terminal + sujets de CE poste du MÊME user, extraits [sujet · date · rôle], "
            + "repère « poste (N sujets) »")
    void hostScopeSearchesTheHostSubjectsOnly() {
        hostTerminal();
        when(messageRepository.searchByContentInWorkspaces(any(), eq(userId), anyString(), any(Pageable.class)))
                .thenReturn(List.of(message(subjectA, "ASSISTANT",
                        "Décision : le jeton Atlantis est stocké dans le coffre Vault.", "2026-09-30T10:00:00Z")));

        agentProvider.enqueueToolCall("recall", "query", "Atlantis", "portee", "poste");
        agentProvider.enqueueFinal("Trouvé.");
        Captor captor = new Captor();
        service.chatStreaming(userId, workspaceId, "qu'a-t-on décidé pour le jeton Atlantis ?", captor);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Collection<UUID>> scope = (ArgumentCaptor) ArgumentCaptor.forClass(Collection.class);
        verify(messageRepository).searchByContentInWorkspaces(scope.capture(), eq(userId), eq("%atlantis%"),
                any(Pageable.class));
        assertThat(scope.getValue()).containsExactlyInAnyOrder(workspaceId, subjectA, subjectB);
        verify(messageRepository, never()).searchByContent(any(), any(), anyString(), any(Pageable.class));

        String toModel = String.join("\n", agentProvider.messageSnapshots);
        assertThat(toModel).contains("[data-platform · 2026-09-30 · assistant]");
        assertThat(toModel).contains("coffre Vault");
        assertThat(toModel).doesNotContain("intrus");
        assertThat(captor.recalled).containsExactly("poste (2 sujets) · data-platform");
    }

    @Test
    @DisplayName("sans portée au terminal du poste : le fil seul, comme avant")
    void defaultScopeIsTheThread() {
        hostTerminal();
        when(messageRepository.searchByContent(eq(workspaceId), eq(userId), anyString(), any(Pageable.class)))
                .thenReturn(List.of());
        agentProvider.enqueueToolCall("recall", "query", "Atlantis");
        agentProvider.enqueueFinal("Rien.");
        service.chatStreaming(userId, workspaceId, "et Atlantis ?", new Captor());

        verify(messageRepository).searchByContent(eq(workspaceId), eq(userId), anyString(), any(Pageable.class));
        verify(messageRepository, never()).searchByContentInWorkspaces(any(), any(), anyString(),
                any(Pageable.class));
    }

    @Test
    @DisplayName("dans un sujet, `portee: poste` envoyé malgré tout est ignoré : le fil, jamais plus")
    void hostScopeIgnoredOutsideTheHostTerminal() {
        plainSubject();
        when(messageRepository.searchByContent(eq(workspaceId), eq(userId), anyString(), any(Pageable.class)))
                .thenReturn(List.of());
        agentProvider.enqueueToolCall("recall", "query", "Atlantis", "portee", "poste");
        agentProvider.enqueueFinal("Rien.");
        service.chatStreaming(userId, workspaceId, "et Atlantis ?", new Captor());

        verify(messageRepository).searchByContent(eq(workspaceId), eq(userId), anyString(), any(Pageable.class));
        verify(messageRepository, never()).searchByContentInWorkspaces(any(), any(), anyString(),
                any(Pageable.class));
        verify(workspaceService, never()).listByHost(any(), any());
    }

    @Test
    @DisplayName("portée poste, sémantique actif : relecture re-filtrée user + fils du poste")
    void hostScopeSemanticIsRefiltered() {
        hostTerminal();
        AtelierMessage hit = message(subjectB, "USER", "on retient le CIDR 10.40.0.0/16 pour lzi",
                "2026-09-20T08:00:00Z");
        when(semanticRecall.isEnabled()).thenReturn(true);
        when(semanticRecall.searchAcross(eq(userId), any(), anyString(), eq(5))).thenReturn(List.of(hit.getId()));
        when(messageRepository.findByUserIdAndWorkspaceIdInAndIdIn(eq(userId), any(), any()))
                .thenReturn(List.of(hit));

        agentProvider.enqueueToolCall("recall", "query", "adressage réseau", "portee", "poste");
        agentProvider.enqueueFinal("Trouvé.");
        service.chatStreaming(userId, workspaceId, "quel adressage réseau ?", new Captor());

        verify(messageRepository).findByUserIdAndWorkspaceIdInAndIdIn(eq(userId), any(), any());
        verify(messageRepository, never()).searchByContentInWorkspaces(any(), any(), anyString(),
                any(Pageable.class));
        assertThat(String.join("\n", agentProvider.messageSnapshots))
                .contains("[lzi · 2026-09-20 · utilisateur]").contains("par le sens");
    }

    // ---------------------------------------------------------------- SF-178-02 : sujets_etat

    @Mock private fr.claudegateway.atelier.poste.SubjectsStateService subjectsState;

    private void wirePosteTools() {
        fr.claudegateway.atelier.poste.PosteToolCatalog catalog = new fr.claudegateway.atelier.poste.PosteToolCatalog();
        service.setPosteTools(catalog, new fr.claudegateway.atelier.poste.PosteToolExecutor(catalog, subjectsState));
    }

    private boolean declares(Workspace workspace, String tool) {
        return service.buildTools(userId, workspace).stream().anyMatch(t -> tool.equals(t.name()));
    }

    @Test
    @DisplayName("SF-178-02 : sujets_etat déclaré au terminal du poste seulement")
    void subjectsStateDeclaredOnlyAtHostTerminal() {
        wirePosteTools();
        assertThat(declares(hostTerminal(), "sujets_etat")).isTrue();
        assertThat(declares(plainSubject(), "sujets_etat")).isFalse();
    }

    @Test
    @DisplayName("SF-178-02 : sujets_etat lit l'état du poste du terminal possédé et le rend au modèle")
    void subjectsStateExecutesForTheOwnedHost() {
        wirePosteTools();
        hostTerminal();
        when(subjectsState.describe(userId, hostId)).thenReturn("## data-platform\n- Parcours : Guidé");
        agentProvider.enqueueToolCall("sujets_etat");
        agentProvider.enqueueFinal("Voilà.");
        service.chatStreaming(userId, workspaceId, "où en est chaque sujet ?", new Captor());

        verify(subjectsState).describe(userId, hostId);
        assertThat(String.join("\n", agentProvider.messageSnapshots)).contains("## data-platform");
    }

    @Test
    @DisplayName("SF-178-02 : appelé dans un sujet malgré tout, sujets_etat est refusé, rien n'est lu")
    void subjectsStateRefusedInASubject() {
        wirePosteTools();
        plainSubject();
        agentProvider.enqueueToolCall("sujets_etat");
        agentProvider.enqueueFinal("Ok.");
        service.chatStreaming(userId, workspaceId, "où en est chaque sujet ?", new Captor());

        verify(subjectsState, never()).describe(any(), any());
        assertThat(String.join("\n", agentProvider.messageSnapshots)).contains("terminal du poste");
    }
}
