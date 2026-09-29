package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
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
 * <b>L'outil {@code recall} fouille l'historique de la conversation</b> (F-162 / SF-162-01) : il est
 * déclaré au modèle sur les deux cibles, sa recherche est <b>isolée</b> {@code user_id} +
 * {@code workspace_id} et <b>bornée</b>, ses extraits portent un repère de tour, et il porte sur
 * <b>tout le fil</b> (jamais la lecture bornée post-frontière).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceRecallTest {

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
        // F-162 / SF-162-06 : sémantique branché mais ÉTEINT par défaut (isEnabled() = false du mock) ⇒
        // recall reste en mot-clé, comme SF-162-01. Les tests sémantiques l'activent explicitement.
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

    private Workspace terminal(WorkspaceExecutionTarget target) {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(target);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        return workspace;
    }

    private AtelierMessage message(String role, String content, OffsetDateTime when) {
        return AtelierMessage.builder()
                .workspaceId(workspaceId).userId(userId).role(role).content(content).createdAt(when)
                .build();
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
    @DisplayName("recall est déclaré au modèle sur les DEUX cibles, avec query obligatoire")
    void recallDeclaredOnBothTargets() {
        for (WorkspaceExecutionTarget target : List.of(WorkspaceExecutionTarget.SANDBOX,
                WorkspaceExecutionTarget.RUNNER)) {
            AgentTool recall = service.buildTools(userId, terminal(target)).stream()
                    .filter(tool -> "recall".equals(tool.name())).findFirst().orElseThrow();
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) recall.inputSchema().get("required");
            assertThat(required).containsExactly("query");
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) recall.inputSchema().get("properties");
            assertThat(properties).containsKey("query");
        }
    }

    @Test
    @DisplayName("recall isole (user_id + workspace_id), borne à N, et étiquette chaque extrait d'un tour")
    void recallIsIsolatedBoundedAndTurnTagged() {
        terminal(WorkspaceExecutionTarget.SANDBOX);
        when(messageRepository.searchByContent(eq(workspaceId), eq(userId), anyString(), any(Pageable.class)))
                .thenReturn(List.of(message("ASSISTANT",
                        "La décision réseau : on part sur un VPC dédié avec un peering vers la prod.",
                        OffsetDateTime.parse("2026-09-12T10:00:00Z"))));
        when(messageRepository.countByWorkspaceIdAndUserIdAndRoleAndCreatedAtLessThanEqual(
                eq(workspaceId), eq(userId), eq("USER"), any())).thenReturn(34L);

        agentProvider.enqueueToolCall("recall", "query", "réseau");
        agentProvider.enqueueFinal("Rappel fait.");
        service.chatStreaming(userId, workspaceId, "quelle était la décision réseau ?", silent());

        // Isolation : la recherche est TOUJOURS scopée au user + workspace du tour.
        ArgumentCaptor<UUID> ws = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> usr = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> term = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository).searchByContent(ws.capture(), usr.capture(), term.capture(), page.capture());
        assertThat(ws.getValue()).isEqualTo(workspaceId);
        assertThat(usr.getValue()).isEqualTo(userId);
        assertThat(term.getValue()).isEqualTo("%réseau%");
        // Bornage : au plus RECALL_MAX_EXTRACTS extraits demandés à la base.
        assertThat(page.getValue().getPageSize()).isEqualTo(5);

        // L'extrait, avec son repère de tour, est bien parti au modèle.
        String toModel = String.join("\n", agentProvider.messageSnapshots);
        assertThat(toModel).contains("tour 34");
        assertThat(toModel).contains("VPC dédié");
    }

    @Test
    @DisplayName("recall porte sur TOUT le fil : jamais la lecture bornée post-frontière (compaction-indépendant)")
    void recallSpansTheWholeThreadIgnoringTheReplayFrontier() {
        terminal(WorkspaceExecutionTarget.SANDBOX);
        when(messageRepository.searchByContent(eq(workspaceId), eq(userId), anyString(), any(Pageable.class)))
                .thenReturn(List.of(message("USER", "mot de passe temporaire : hunter2",
                        OffsetDateTime.parse("2026-01-01T09:00:00Z"))));
        when(messageRepository.countByWorkspaceIdAndUserIdAndRoleAndCreatedAtLessThanEqual(
                eq(workspaceId), eq(userId), eq("USER"), any())).thenReturn(3L);

        agentProvider.enqueueToolCall("recall", "query", "hunter2");
        agentProvider.enqueueFinal("Retrouvé.");
        service.chatStreaming(userId, workspaceId, "c'était quoi le mot de passe temporaire ?", silent());

        // La requête plein-texte est utilisée ; la lecture bornée par la frontière de rejeu, jamais —
        // c'est ce qui permet à recall de retrouver un tour d'avant une compaction / un « Nouveau départ ».
        verify(messageRepository).searchByContent(eq(workspaceId), eq(userId), anyString(), any(Pageable.class));
        verify(messageRepository, never())
                .findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                        any(), any(), any());
        assertThat(String.join("\n", agentProvider.messageSnapshots)).contains("hunter2");
    }

    @Test
    @DisplayName("recall sans mot-clé : résultat d'erreur, aucune recherche en base")
    void recallWithBlankQueryIsAnError() {
        terminal(WorkspaceExecutionTarget.SANDBOX);
        agentProvider.enqueueToolCall("recall", "query", "");
        agentProvider.enqueueFinal("Je précise ma recherche.");
        service.chatStreaming(userId, workspaceId, "rappelle-toi", silent());

        verify(messageRepository, never()).searchByContent(any(), any(), anyString(), any(Pageable.class));
        assertThat(String.join("\n", agentProvider.messageSnapshots)).contains("Requête requise pour recall");
    }

    @Test
    @DisplayName("recall sans correspondance : message neutre « aucun extrait trouvé »")
    void recallWithNoMatchIsNeutral() {
        terminal(WorkspaceExecutionTarget.SANDBOX);
        when(messageRepository.searchByContent(eq(workspaceId), eq(userId), anyString(), any(Pageable.class)))
                .thenReturn(List.of());
        agentProvider.enqueueToolCall("recall", "query", "licorne");
        agentProvider.enqueueFinal("Rien trouvé.");
        service.chatStreaming(userId, workspaceId, "on a parlé de licornes ?", silent());

        assertThat(String.join("\n", agentProvider.messageSnapshots)).contains("Aucun extrait trouvé");
    }

    // ---------------------------------------------------------------- F-162 / SF-162-03 : visibilité

    /** Listener capteur : garde les étapes reçues et le repère de rappel émis. */
    private static final class Captor implements AtelierProgressListener {
        final List<AtelierStepEvent> steps = new java.util.ArrayList<>();
        final List<String> recalled = new java.util.ArrayList<>();

        @Override
        public void onAction(AtelierStepEvent step) {
            steps.add(step);
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

    @Test
    @DisplayName("SF-162-03 : l'étape de recall est SPÉCIALISÉE (type « recall »), pas la recherche générique")
    void recallStepIsSpecialized() {
        terminal(WorkspaceExecutionTarget.SANDBOX);
        when(messageRepository.searchByContent(eq(workspaceId), eq(userId), anyString(), any(Pageable.class)))
                .thenReturn(List.of(message("USER", "on avait choisi Postgres pour la base",
                        OffsetDateTime.parse("2026-02-01T09:00:00Z"))));
        when(messageRepository.countByWorkspaceIdAndUserIdAndRoleAndCreatedAtLessThanEqual(
                eq(workspaceId), eq(userId), eq("USER"), any())).thenReturn(7L);
        Captor captor = new Captor();

        agentProvider.enqueueToolCall("recall", "query", "base");
        agentProvider.enqueueFinal("Rappel fait.");
        service.chatStreaming(userId, workspaceId, "quelle base déjà ?", captor);

        assertThat(captor.steps).anySatisfy(step -> {
            assertThat(step.type()).isEqualTo("recall");
            assertThat(step.path()).isEqualTo("base");
        });
    }

    @Test
    @DisplayName("SF-162-03 : recall avec extraits → repère « tour N » émis ; sans extrait → rien")
    void recallEmitsRetrievedTurnMarker() {
        terminal(WorkspaceExecutionTarget.SANDBOX);
        when(messageRepository.searchByContent(eq(workspaceId), eq(userId), anyString(), any(Pageable.class)))
                .thenReturn(List.of(message("ASSISTANT", "La décision réseau : un VPC dédié.",
                        OffsetDateTime.parse("2026-09-12T10:00:00Z"))));
        when(messageRepository.countByWorkspaceIdAndUserIdAndRoleAndCreatedAtLessThanEqual(
                eq(workspaceId), eq(userId), eq("USER"), any())).thenReturn(34L);
        Captor captor = new Captor();

        agentProvider.enqueueToolCall("recall", "query", "réseau");
        agentProvider.enqueueFinal("Rappel fait.");
        service.chatStreaming(userId, workspaceId, "la décision réseau ?", captor);

        assertThat(captor.recalled).containsExactly("tour 34");
    }

    @Test
    @DisplayName("SF-162-03 : recall sans extrait n'émet aucun repère")
    void recallWithNoMatchEmitsNoMarker() {
        terminal(WorkspaceExecutionTarget.SANDBOX);
        when(messageRepository.searchByContent(eq(workspaceId), eq(userId), anyString(), any(Pageable.class)))
                .thenReturn(List.of());
        Captor captor = new Captor();

        agentProvider.enqueueToolCall("recall", "query", "licorne");
        agentProvider.enqueueFinal("Rien trouvé.");
        service.chatStreaming(userId, workspaceId, "des licornes ?", captor);

        assertThat(captor.recalled).isEmpty();
    }

    // ---------------------------------------------------------------- F-162 / SF-162-06 : sémantique

    @Test
    @DisplayName("SF-162-06 : sémantique actif → recherche par ids (isolée), PAS le mot-clé ; « par le sens »")
    void semanticActiveUsesVectorSearchNotKeyword() {
        terminal(WorkspaceExecutionTarget.SANDBOX);
        UUID hitId = UUID.randomUUID();
        when(semanticRecall.isEnabled()).thenReturn(true);
        when(semanticRecall.search(eq(userId), eq(workspaceId), eq("adressage réseau"), eq(5)))
                .thenReturn(List.of(hitId));
        AtelierMessage hit = AtelierMessage.builder().id(hitId).workspaceId(workspaceId).userId(userId)
                .role("ASSISTANT").content("On a mis en place un VPC avec un CIDR 10.0.0.0/16.")
                .createdAt(OffsetDateTime.parse("2026-05-01T10:00:00Z")).build();
        when(messageRepository.findByWorkspaceIdAndUserIdAndIdIn(eq(workspaceId), eq(userId), any()))
                .thenReturn(List.of(hit));
        when(messageRepository.countByWorkspaceIdAndUserIdAndRoleAndCreatedAtLessThanEqual(
                eq(workspaceId), eq(userId), eq("USER"), any())).thenReturn(12L);

        agentProvider.enqueueToolCall("recall", "query", "adressage réseau");
        agentProvider.enqueueFinal("Rappel fait.");
        service.chatStreaming(userId, workspaceId, "comment on adresse le réseau ?", silent());

        // Le sémantique est isolé (user + workspace) et le mot-clé n'est PAS emprunté quand il répond.
        verify(semanticRecall).search(eq(userId), eq(workspaceId), eq("adressage réseau"), eq(5));
        verify(messageRepository).findByWorkspaceIdAndUserIdAndIdIn(eq(workspaceId), eq(userId), any());
        verify(messageRepository, never()).searchByContent(any(), any(), anyString(), any(Pageable.class));
        String toModel = String.join("\n", agentProvider.messageSnapshots);
        assertThat(toModel).contains("par le sens");
        assertThat(toModel).contains("tour 12");
        assertThat(toModel).contains("CIDR 10.0.0.0/16");
    }

    @Test
    @DisplayName("SF-162-06 : sémantique 0 résultat → REPLI mot-clé dans le même appel")
    void semanticEmptyFallsBackToKeyword() {
        terminal(WorkspaceExecutionTarget.SANDBOX);
        when(semanticRecall.isEnabled()).thenReturn(true);
        when(semanticRecall.search(eq(userId), eq(workspaceId), anyString(), eq(5))).thenReturn(List.of());
        when(messageRepository.searchByContent(eq(workspaceId), eq(userId), anyString(), any(Pageable.class)))
                .thenReturn(List.of(message("USER", "on avait choisi Postgres",
                        OffsetDateTime.parse("2026-02-01T09:00:00Z"))));
        when(messageRepository.countByWorkspaceIdAndUserIdAndRoleAndCreatedAtLessThanEqual(
                eq(workspaceId), eq(userId), eq("USER"), any())).thenReturn(4L);

        agentProvider.enqueueToolCall("recall", "query", "base de données");
        agentProvider.enqueueFinal("Rappel fait.");
        service.chatStreaming(userId, workspaceId, "quelle base ?", silent());

        // Le sémantique n'a rien trouvé → le mot-clé prend le relais (le message reste retrouvable).
        verify(messageRepository).searchByContent(eq(workspaceId), eq(userId), anyString(), any(Pageable.class));
        assertThat(String.join("\n", agentProvider.messageSnapshots)).contains("Postgres");
    }

    @Test
    @DisplayName("SF-162-06 : sémantique en échec (relecture jette) → REPLI mot-clé, aucune exception")
    void semanticFailureFallsBackToKeyword() {
        terminal(WorkspaceExecutionTarget.SANDBOX);
        when(semanticRecall.isEnabled()).thenReturn(true);
        when(semanticRecall.search(eq(userId), eq(workspaceId), anyString(), eq(5)))
                .thenReturn(List.of(UUID.randomUUID()));
        when(messageRepository.findByWorkspaceIdAndUserIdAndIdIn(any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));
        when(messageRepository.searchByContent(eq(workspaceId), eq(userId), anyString(), any(Pageable.class)))
                .thenReturn(List.of(message("USER", "repli mot-clé OK",
                        OffsetDateTime.parse("2026-03-01T09:00:00Z"))));
        when(messageRepository.countByWorkspaceIdAndUserIdAndRoleAndCreatedAtLessThanEqual(
                eq(workspaceId), eq(userId), eq("USER"), any())).thenReturn(2L);

        agentProvider.enqueueToolCall("recall", "query", "quelque chose");
        agentProvider.enqueueFinal("Rappel fait.");
        service.chatStreaming(userId, workspaceId, "rappelle", silent());

        verify(messageRepository).searchByContent(eq(workspaceId), eq(userId), anyString(), any(Pageable.class));
        assertThat(String.join("\n", agentProvider.messageSnapshots)).contains("repli mot-clé OK");
    }

    @Test
    @DisplayName("SF-162-06 : à l'écriture, la parole utilisateur ET la réponse sont embeddées (best-effort)")
    void embedsMessagesOnWrite() {
        terminal(WorkspaceExecutionTarget.SANDBOX);
        agentProvider.enqueueFinal("Voici ma réponse.");

        service.chatStreaming(userId, workspaceId, "ma question du jour", silent());

        verify(semanticRecall).embedAsync(any(UUID.class), eq("ma question du jour"));
        verify(semanticRecall).embedAsync(any(UUID.class), eq("Voici ma réponse."));
    }
}
