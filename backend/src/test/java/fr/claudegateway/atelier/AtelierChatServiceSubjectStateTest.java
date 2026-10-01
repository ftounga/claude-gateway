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
 * {@code PLAN-ACTION.md}) est lu à chaque tour pour reprendre le fil sans un {@code read_file}
 * d'amorçage.
 *
 * <p>F-171 / SF-171-01 — ce contenu, réécrit à chaque tour, a quitté le <b>bloc système</b> pour le
 * <b>MESSAGE</b> du tour (préfixé à la consigne, sous le dernier breakpoint, patron F-137). Le
 * contenu est inchangé à l'octet près ; seul son emplacement change. On vérifie donc : (1) le bloc
 * système <b>ne porte plus</b> l'état du sujet et reste <b>byte-stable</b> d'un tour à l'autre même
 * quand l'état change (c'est LE test du gain de cache) ; (2) l'état voyage bien dans le message.</p>
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

    /** La consigne système effectivement envoyée au fournisseur pour un tour trivial. */
    private String systemPrompt() {
        agentProvider.enqueueFinal("fini");
        service.chat(userId, workspaceId, "bonjour");
        return agentProvider.lastRequest.system();
    }

    /** Le message (consigne) effectivement envoyé — porte désormais l'état du sujet (F-171). */
    private String message() {
        return String.valueOf(agentProvider.lastRequest.messages());
    }

    @Test
    @DisplayName("F-171 : STATE/PLAN présents voyagent dans le MESSAGE, pas dans le système")
    void injectsStateAndPlanContentInTheMessage() {
        when(workspaceService.readFile(userId, workspaceId, "STATE.md"))
                .thenReturn("Statut : migration DNS en cours, étape 3/5.");
        when(workspaceService.readFile(userId, workspaceId, "PLAN-ACTION.md"))
                .thenReturn("- [ ] basculer les enregistrements MX\n- [x] geler la zone");

        String system = systemPrompt();

        // Déplacé : le bloc système ne porte plus l'état du sujet.
        assertThat(system).doesNotContain("--- État courant du sujet (STATE.md / PLAN-ACTION.md) ---");
        assertThat(system).doesNotContain("migration DNS en cours, étape 3/5.");

        // Même contenu, à l'octet près, désormais dans le message du tour.
        String message = message();
        assertThat(message).contains("--- État courant du sujet (STATE.md / PLAN-ACTION.md) ---");
        assertThat(message).contains("### STATE.md");
        assertThat(message).contains("migration DNS en cours, étape 3/5.");
        assertThat(message).contains("### PLAN-ACTION.md");
        assertThat(message).contains("basculer les enregistrements MX");
    }

    @Test
    @DisplayName("aucun fichier d'état : aucun bloc n'est ajouté, ni au système ni au message")
    void noStateMeansNoBlock() {
        when(workspaceService.readFile(userId, workspaceId, "STATE.md"))
                .thenThrow(new InvalidFilePathException("absent"));
        when(workspaceService.readFile(userId, workspaceId, "PLAN-ACTION.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        systemPrompt();

        assertThat(agentProvider.lastRequest.system())
                .doesNotContain("--- État courant du sujet (STATE.md / PLAN-ACTION.md) ---");
        assertThat(message())
                .doesNotContain("--- État courant du sujet (STATE.md / PLAN-ACTION.md) ---");
    }

    @Test
    @DisplayName("un état trop long est tronqué dans le message, et la coupe se dit")
    void oversizedStateIsTruncated() {
        String huge = "S".repeat(6_000 + 500);
        when(workspaceService.readFile(userId, workspaceId, "STATE.md")).thenReturn(huge);
        when(workspaceService.readFile(userId, workspaceId, "PLAN-ACTION.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        systemPrompt();
        String message = message();

        assertThat(message).contains("… (état tronqué)");
        // La borne par fichier est bien appliquée : le contenu brut complet n'est jamais injecté entier.
        assertThat(message).doesNotContain("S".repeat(6_000 + 1));
    }

    @Test
    @DisplayName("F-171 : le préfixe système est byte-stable d'un tour à l'autre même quand l'état change")
    void systemPrefixIsByteStableEvenWhenStateChanges() {
        // L'état du sujet change entre les deux tours — exactement le cas qui, avant F-171, cassait le
        // cache au niveau système. Désormais l'état vit dans le message : le préfixe système ne bouge pas.
        when(workspaceService.readFile(userId, workspaceId, "STATE.md"))
                .thenReturn("Statut tour 1.", "Statut tour 2, tout a changé.");
        when(workspaceService.readFile(userId, workspaceId, "PLAN-ACTION.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String firstSystem = systemPrompt();
        String firstMessage = message();
        String secondSystem = systemPrompt();
        String secondMessage = message();

        // LE test du gain : le bloc système est byte-identique bien que l'état ait changé.
        assertThat(secondSystem).isEqualTo(firstSystem);
        // Et l'état, lui, a bien changé — dans le message (sous le dernier breakpoint).
        assertThat(firstMessage).contains("Statut tour 1.");
        assertThat(secondMessage).contains("Statut tour 2, tout a changé.");
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

        systemPrompt();
        assertThat(agentProvider.lastRequest.system())
                .doesNotContain("--- État courant du sujet (STATE.md / PLAN-ACTION.md) ---");
        assertThat(message())
                .doesNotContain("--- État courant du sujet (STATE.md / PLAN-ACTION.md) ---");
    }
}
