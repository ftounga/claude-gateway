package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.UsageSnapshot;

/**
 * Plafond de consommation d'un message et relevé de ce qu'il coûte (F-39 / SF-39-15, lot 8).
 *
 * <p>La boucle bornait le nombre d'itérations et la durée d'un tour ; elle ne bornait pas ce qu'un
 * <b>seul message</b> pouvait consommer — le quota étant vérifié avant le tour et enregistré après.
 * Ce fichier fige les quatre points du lot : le tour s'arrête, il le dit d'une façon distincte du
 * budget de temps, sa consommation est relayée au fil de l'eau, et elle survit au rechargement.</p>
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceBudgetTest {

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

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    /** Listener de capture : n'enregistre que la consommation relayée. */
    private static final class ProgressListener implements AtelierProgressListener {
        final List<Long> progress = new ArrayList<>();

        @Override
        public void onAction(AtelierStepEvent step) {
            // Sans objet ici.
        }

        @Override
        public void onText(String text) {
            // Sans objet ici.
        }

        @Override
        public void onProgress(long tokens) {
            progress.add(tokens);
        }
    }

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        // Isolation : le workspace est toujours résolu par (userId, workspaceId). Souple, deux tests
        // de ce fichier ne passant pas par la boucle.
        lenient().when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        lenient().when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        lenient().when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        lenient().when(workspaceService.tree(any(), any())).thenReturn(List.of());
        lenient().when(workspaceService.readFile(any(), any(), any())).thenReturn("contenu");
    }

    /** Construit le service avec un plafond de message donné (en tokens traités). */
    private void serviceWithTurnCap(Long maxTurnTokens) {
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        maxTurnTokens, null, true));
    }

    private void hostedWithRemainingQuota(long remainingTokens) {
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        lenient().when(quotaService.currentUsage(userId))
                .thenReturn(new UsageSnapshot(0L, 12_000_000L, remainingTokens, null, null));
    }

    // ------------------------------------------------------------ le plafond arrête le tour

    @Test
    void stopsTheTurnWhenTheNextIterationWouldCrossTheCap() {
        hostedWithRemainingQuota(12_000_000L);
        serviceWithTurnCap(20L);
        // Chaque itération pèse 10 tokens. Deux passent (10 puis 20) ; la troisième est projetée à
        // 30 et refusée AVANT l'appel (D-L8-2).
        for (int i = 0; i < 5; i++) {
            agentProvider.enqueueToolCallCosting("read_file", 5, 5);
        }

        AtelierChatResult result = service.chat(userId, workspaceId, "lis tout");

        assertThat(result.reply()).isEqualTo(AtelierChatService.SPEND_CAP_REPLY);
        assertThat(result.budgetReached()).isTrue();
        assertThat(result.inputTokens() + result.outputTokens()).isEqualTo(20L);
        // Trois tours scriptés n'ont jamais été demandés : la boucle s'est arrêtée, elle n'a pas
        // simplement constaté après coup.
        assertThat(agentProvider.remaining()).isEqualTo(3);
    }

    @Test
    void saysSomethingElseThanTheTimeBudget() {
        // Deux causes d'arrêt, deux textes : les confondre ferait proposer « racheter des tokens »
        // à quelqu'un que la montre a arrêté (D-L8-5).
        assertThat(AtelierChatService.SPEND_CAP_REPLY)
                .isNotEqualTo(AtelierChatService.BUDGET_REACHED_REPLY)
                .contains("plafond de consommation");
    }

    @Test
    void anOrdinaryTurnIsUntouchedByTheCap() {
        hostedWithRemainingQuota(12_000_000L);
        serviceWithTurnCap(null); // défaut : 1 500 000 tokens
        agentProvider.enqueueToolCallCosting("read_file", 5, 5);
        agentProvider.enqueueFinal("Voilà.");

        AtelierChatResult result = service.chat(userId, workspaceId, "lis notes.txt");

        assertThat(result.reply()).isEqualTo("Voilà.");
        assertThat(result.budgetReached()).isFalse();
        assertThat(agentProvider.remaining()).isZero();
    }

    // ------------------------------------------------------------ le quota borne le plafond

    @Test
    void neverSpendsMoreThanTheRemainingQuota() {
        // Le réglage est large, le quota restant ne l'est pas : c'est lui qui borne. Un message ne
        // consomme jamais plus que ce qui a été payé (règle de F-36 transposée).
        hostedWithRemainingQuota(20L);
        serviceWithTurnCap(1_500_000L);
        for (int i = 0; i < 5; i++) {
            agentProvider.enqueueToolCallCosting("read_file", 5, 5);
        }

        AtelierChatResult result = service.chat(userId, workspaceId, "lis tout");

        assertThat(result.budgetReached()).isTrue();
        assertThat(result.inputTokens() + result.outputTokens()).isEqualTo(20L);
    }

    @Test
    void alwaysRunsTheFirstIterationEvenWithAnExhaustedQuota() {
        // D-L8-3 : un tour qui n'a rien fait, rien dit et rien coûté se lirait comme une panne.
        hostedWithRemainingQuota(0L);
        serviceWithTurnCap(1_500_000L);
        agentProvider.enqueueFinal("Bonjour.");

        AtelierChatResult result = service.chat(userId, workspaceId, "bonjour");

        assertThat(result.reply()).isEqualTo("Bonjour.");
        assertThat(result.budgetReached()).isFalse();
    }

    @Test
    void byokNeverReadsTheQuota() {
        // En BYOK les tokens sont sur le compte du client (SF-28-06) : ni contrôle, ni décompte, ni
        // lecture du quota — mais le plafond de message, lui, s'applique.
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.of("sk-ant-user-key"));
        serviceWithTurnCap(20L);
        for (int i = 0; i < 5; i++) {
            agentProvider.enqueueToolCallCosting("read_file", 5, 5);
        }

        AtelierChatResult result = service.chat(userId, workspaceId, "lis tout");

        assertThat(result.budgetReached()).isTrue();
        verify(quotaService, never()).currentUsage(any());
        verify(quotaService, never()).assertWithinQuota(any());
        verify(quotaService, never()).recordUsage(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void theQuotaStillCountsWhatWasActuallySpent() {
        hostedWithRemainingQuota(12_000_000L);
        serviceWithTurnCap(20L);
        for (int i = 0; i < 5; i++) {
            agentProvider.enqueueToolCallCosting("read_file", 7, 3);
        }

        service.chat(userId, workspaceId, "lis tout");

        // Deux itérations à 7/3 : le décompte porte sur ce qui a réellement été traité, plafond
        // atteint ou non.
        verify(quotaService).recordUsage(userId, 14, 6);
    }

    // ------------------------------------------------------------ la consommation est visible

    @Test
    void relaysTheRunningTotalAfterEachIteration() {
        hostedWithRemainingQuota(12_000_000L);
        serviceWithTurnCap(null);
        agentProvider.enqueueToolCallCosting("read_file", 5, 5);
        agentProvider.enqueueToolCallCosting("read_file", 5, 5);
        agentProvider.enqueueFinal("Voilà.");
        ProgressListener listener = new ProgressListener();

        service.chatStreaming(userId, workspaceId, "lis a.txt puis b.txt", listener);

        // Un relevé par itération, cumul croissant — c'est ce qui remplit les tokens de la ligne
        // vivante, muette sur la boucle maison jusqu'ici (acquis §4 n°5).
        assertThat(listener.progress).containsExactly(10L, 20L, 30L);
    }

    @Test
    void persistsWhatTheTurnCostAndWhyItStopped() {
        hostedWithRemainingQuota(12_000_000L);
        serviceWithTurnCap(20L);
        for (int i = 0; i < 5; i++) {
            agentProvider.enqueueToolCallCosting("read_file", 5, 5);
        }

        service.chat(userId, workspaceId, "lis tout");

        ArgumentCaptor<AtelierMessage> saved = ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        AtelierMessage assistant = saved.getAllValues().stream()
                .filter(m -> "ASSISTANT".equals(m.getRole()))
                .reduce((first, second) -> second)
                .orElseThrow();
        // Le relevé se range dans la colonne d'affichage existante (D-L8-6) : au rechargement, un
        // tour arrêté sur le plafond dit encore pourquoi.
        assertThat(assistant.getTerminalJson())
                .contains("\"budgetReached\":true")
                .contains("\"inputTokens\":10")
                .contains("\"outputTokens\":10");
    }

    @Test
    void writesNoReportWhenNothingWasMeasured() {
        // Rien à dire : pas de document. Écrire un relevé vide ferait afficher « 0 token » là où la
        // mesure manque (même règle que F-30 / SF-30-05).
        assertThat(new AtelierTurnReport(0L, 0L, 3L, false, false).toJson()).isNull();
        assertThat(new AtelierTurnReport(0L, 0L, 0L, true, false).toJson())
                .contains("\"interrupted\":true");
    }
}
