package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * F-148 / SF-148-05 — l'état courant du sujet (contenu borné de {@code STATE.md} et
 * {@code PLAN-ACTION.md}) rejoint le préfixe de la consigne, pour reprendre le fil sans un tour
 * {@code read_file} d'amorçage.
 *
 * <p>Le contenu est lu là où les fichiers vivent (même {@code readOptional} target-aware que le
 * {@code CLAUDE.md}), borné par fichier, et injecté verbatim : à contenu stable, la consigne est
 * byte-identique d'un tour au suivant — le cache de prompt (F-134) tient.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceSubjectStateTest {

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
        service = new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient,
                        new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null,
                        null, true));

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
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
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        // CLAUDE.md absent par défaut : on isole l'effet de l'état du sujet.
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));
    }

    private String systemPrompt() {
        agentProvider.enqueueFinal("fini");
        service.chat(userId, workspaceId, "bonjour");
        return agentProvider.lastRequest.system();
    }

    @Test
    @DisplayName("STATE.md et PLAN-ACTION.md présents : leur contenu rejoint le préfixe")
    void injectsStateAndPlanContent() {
        when(workspaceService.readFile(userId, workspaceId, "STATE.md"))
                .thenReturn("Statut : migration DNS en cours, étape 3/5.");
        when(workspaceService.readFile(userId, workspaceId, "PLAN-ACTION.md"))
                .thenReturn("- [ ] basculer les enregistrements MX\n- [x] geler la zone");

        String system = systemPrompt();

        assertThat(system).contains("--- État courant du sujet (STATE.md / PLAN-ACTION.md) ---");
        assertThat(system).contains("### STATE.md");
        assertThat(system).contains("migration DNS en cours, étape 3/5.");
        assertThat(system).contains("### PLAN-ACTION.md");
        assertThat(system).contains("basculer les enregistrements MX");
    }

    @Test
    @DisplayName("aucun fichier d'état : aucun bloc n'est ajouté")
    void noStateMeansNoBlock() {
        when(workspaceService.readFile(userId, workspaceId, "STATE.md"))
                .thenThrow(new InvalidFilePathException("absent"));
        when(workspaceService.readFile(userId, workspaceId, "PLAN-ACTION.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        assertThat(systemPrompt())
                .doesNotContain("--- État courant du sujet (STATE.md / PLAN-ACTION.md) ---");
    }

    @Test
    @DisplayName("un état trop long est tronqué, et la coupe se dit")
    void oversizedStateIsTruncated() {
        String huge = "S".repeat(6_000 + 500);
        when(workspaceService.readFile(userId, workspaceId, "STATE.md")).thenReturn(huge);
        when(workspaceService.readFile(userId, workspaceId, "PLAN-ACTION.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).contains("… (état tronqué)");
        // La borne par fichier est bien appliquée : le contenu brut complet n'est jamais injecté entier.
        assertThat(system).doesNotContain("S".repeat(6_000 + 1));
    }

    @Test
    @DisplayName("à contenu identique, la consigne est byte-identique d'un tour au suivant (cache)")
    void stablePrefixAcrossTurns() {
        when(workspaceService.readFile(userId, workspaceId, "STATE.md"))
                .thenReturn("Statut stable.");
        when(workspaceService.readFile(userId, workspaceId, "PLAN-ACTION.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String first = systemPrompt();
        String second = systemPrompt();

        assertThat(second).isEqualTo(first);
        assertThat(first).contains("Statut stable.");
    }

    @Test
    @DisplayName("les lectures d'état sont scoppées au couple (utilisateur, projet) du tour")
    void stateReadsAreScopedToTheTurn() {
        when(workspaceService.readFile(userId, workspaceId, "STATE.md")).thenReturn("Statut.");
        when(workspaceService.readFile(userId, workspaceId, "PLAN-ACTION.md")).thenReturn("Plan.");

        systemPrompt();

        verify(workspaceService).readFile(userId, workspaceId, "STATE.md");
        verify(workspaceService).readFile(userId, workspaceId, "PLAN-ACTION.md");
    }

    @Test
    @DisplayName("au terminal du poste, aucun état de sujet n'est injecté")
    void hostTerminalHasNoSubjectState() {
        Workspace host = new Workspace();
        host.setId(workspaceId);
        host.setUserId(userId);
        host.setSource(WorkspaceSource.ARCHIVE);
        host.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        host.setHostId(UUID.randomUUID());
        host.setHostTerminal(true);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(host);
        when(runnerToolGateway.listFiles(any(), any()))
                .thenReturn(new fr.claudegateway.runner.channel.RunnerCallResult(
                        true, "", false, null, 5L, null, null, null, "", false));
        // Même si la machine renvoyait un contenu, le terminal n'a pas de sujet courant.
        when(runnerToolGateway.readFile(any(), any(), any()))
                .thenReturn(new fr.claudegateway.runner.channel.RunnerCallResult(
                        true, "peu importe", false, null, 5L, null, null, null, "", false));

        assertThat(systemPrompt())
                .doesNotContain("--- État courant du sujet (STATE.md / PLAN-ACTION.md) ---");
    }
}
