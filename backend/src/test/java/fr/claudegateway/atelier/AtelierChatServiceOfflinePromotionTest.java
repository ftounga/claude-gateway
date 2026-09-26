package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpoint;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.governance.GovernanceControl;
import fr.claudegateway.governance.control.JugeFinDeTourControl;
import fr.claudegateway.governance.control.PromotionDetteBloquanteControl;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * <b>Runner hors ligne : plus de refus en boucle, et le marqueur ne fuite pas</b> (F-125 / SF-125-06b).
 *
 * <p>Le constat de production (CAGIP) : runner déconnecté, trois refus de fin de tour qui redemandaient
 * d'écrire dans la carte, trois réponses « je ne peux pas écrire ». Depuis SF-125-06b, les contrôles
 * de fin de tour ne lisent plus de marqueur et rendent toujours {@code proceed()} : le tour se clôt
 * <b>en une fois</b>, sans boucle. On vérifie aussi que {@code stripTurnMetadata} ôte encore un
 * marqueur hérité (un poste activé avant re-seed pouvait encore en émettre).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceOfflinePromotionTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private fr.claudegateway.runner.exec.RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final fr.claudegateway.runner.channel.RunnerTarget runnerTarget =
            new fr.claudegateway.runner.channel.RunnerTarget(hostId, workspaceId, "projet");

    /** Réponse finale portant un marqueur hérité — il ne doit pas fuiter dans le rendu. */
    private static final String FINAL_WITH_LEGACY_MARKER =
            "Je ne peux pas écrire dans la carte : le runner n'est pas connecté."
                    + "\n\n<!-- fin-de-tour: promotion=cluster atlas; dette=0 -->";
    private static final String FINAL_RENDU =
            "Je ne peux pas écrire dans la carte : le runner n'est pas connecté.";

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        // SF-125-06b : les contrôles réels, désormais neutralisés (aucune dépendance au marqueur).
        GovernanceControl juge = new JugeFinDeTourControl();
        GovernanceControl dette = new PromotionDetteBloquanteControl();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true),
                new AtelierCheckpointRunner(List.of(adapt(juge), adapt(dette))));

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
        when(runnerToolGateway.listFiles(eq(runnerTarget), anyString())).thenReturn(ok(""));
        when(runnerToolGateway.readFile(eq(runnerTarget), anyString(), anyString()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));
        // Runner hors ligne : toute écriture échoue.
        when(runnerToolGateway.writeFile(eq(runnerTarget), anyString(), anyString(), anyString()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
    }

    /** Un contrôle de gouvernance, observé dans la boucle sans monter tout le catalogue. */
    private AtelierCheckpoint adapt(GovernanceControl control) {
        return new AtelierCheckpoint() {
            @Override
            public AtelierCheckpointKind kind() {
                return AtelierCheckpointKind.END_OF_TURN;
            }

            @Override
            public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
                return control.evaluate(context);
            }
        };
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    @Test
    @DisplayName("runner hors ligne : le tour se clôt SANS BOUCLE — et depuis F-161/SF-161-02, sans le dernier appel")
    void offlineRunnerClosesTheTurnInOnePass() {
        agentProvider.enqueueToolCall("write_file", "path", "plateformes.md", "content", "cluster atlas");
        agentProvider.enqueueFinal(FINAL_WITH_LEGACY_MARKER);
        agentProvider.enqueueFinal("ne devrait jamais être demandé");

        AtelierChatResult result = service.chat(userId, workspaceId, "range le cluster dans la carte");

        // LA GARANTIE D'ORIGINE (F-125 / SF-125-06b) TIENT TOUJOURS : aucun refus de fin de tour ne
        // relance le fournisseur — c'est le défaut de production (CAGIP) que cette classe protège.
        //
        // Ce qui a changé, et qui est le GAIN de F-161 / SF-161-02 : la boucle s'arrête désormais
        // UN APPEL PLUS TÔT. Avant, on payait un appel complet, sur tout le contexte, pour que le
        // modèle écrive « je ne peux pas écrire : le runner n'est pas connecté » — c'est-à-dire ce
        // que la gateway savait déjà. Ce message est maintenant rendu pour zéro jeton, enrichi des
        // étapes déjà abouties. Le compromis est celui du cadrage F-161 §5.
        assertThat(agentProvider.remaining())
                .as("deux tours restent au script : celui qu'on n'achète plus, et celui d'après")
                .isEqualTo(2);
        assertThat(result.stoppedByMachine()).isTrue();
        assertThat(result.reply())
                .startsWith(fr.claudegateway.runner.door.RunnerStopSummary.PREFIX);
        // Aucun marqueur hérité ne peut fuiter : la réponse ne vient plus du modèle du tout.
        assertThat(result.reply()).doesNotContain("fin-de-tour");
    }

    // ---------------------------------------------- F-125 / SF-125-01 : le marqueur ne fuite pas

    @Test
    @DisplayName("le marqueur de fin de tour est retiré de la réponse rendue")
    void theTurnMarkerIsStrippedFromTheReply() {
        assertThat(AtelierChatService.stripTurnMetadata(
                "Oui, ça s'est bien passé.\n\n<!-- fin-de-tour: promotion=aucune; promu=aucune; dette=0 -->"))
                .isEqualTo("Oui, ça s'est bien passé.");
        // Casse et espaces libres, plusieurs occurrences.
        assertThat(AtelierChatService.stripTurnMetadata(
                "<!--FIN-DE-TOUR: dette=2 -->A<!-- fin-de-tour: dette=0 -->B")).isEqualTo("AB");
        // Un autre commentaire HTML n'est pas touché.
        assertThat(AtelierChatService.stripTurnMetadata("Texte <!-- todo -->"))
                .isEqualTo("Texte <!-- todo -->");
        // Sans marqueur, la réponse est rendue à l'identique (pas de recompactage intempestif).
        assertThat(AtelierChatService.stripTurnMetadata("Réponse.\n")).isEqualTo("Réponse.\n");
        assertThat(AtelierChatService.stripTurnMetadata(null)).isNull();
    }

    @Test
    @DisplayName("une réponse réduite au seul marqueur devient vide (repli côté appelant)")
    void aReplyReducedToTheMarkerBecomesBlank() {
        assertThat(AtelierChatService.stripTurnMetadata(
                "<!-- fin-de-tour: promotion=aucune; dette=0 -->")).isBlank();
    }
}
