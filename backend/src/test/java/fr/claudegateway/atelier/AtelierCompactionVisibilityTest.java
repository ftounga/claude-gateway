package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;

/**
 * <b>La compaction est visible</b> (F-162 / SF-162-03) : elle relaie au listener une transition
 * « démarrée » puis « terminée · N tours », <b>uniquement</b> quand une compaction a bien lieu, et son
 * émission est <b>best-effort</b> — un listener qui échoue ne fait jamais échouer la compaction (F-117).
 */
@ExtendWith(MockitoExtension.class)
class AtelierCompactionVisibilityTest {

    @Mock private AtelierMessageRepository messageRepository;
    @Mock private WorkspaceRepository workspaceRepository;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
    }

    /** Service câblé sur un fournisseur donné, seuil bas et 2 messages récents gardés entiers. */
    private AtelierCompactionService service(AiAgentProvider provider) {
        AtelierCompactionProperties props = new AtelierCompactionProperties(true, 100, 2);
        AtelierProperties atelier = new AtelierProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, null, true);
        return new AtelierCompactionService(messageRepository, workspaceRepository, provider, props,
                atelier);
    }

    private AtelierMessage message(String role, String content, OffsetDateTime at) {
        return AtelierMessage.builder().id(UUID.randomUUID()).workspaceId(workspaceId).userId(userId)
                .role(role).content(content).createdAt(at).build();
    }

    private String longText(String prefix) {
        return prefix + " " + "x".repeat(500);
    }

    private void stubHistory(List<AtelierMessage> history) {
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);
    }

    /** Six messages (3 échanges) longs : au-dessus du seuil, on garde les 2 derniers entiers. */
    private List<AtelierMessage> threeExchanges() {
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(3);
        List<AtelierMessage> history = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            history.add(message("USER", longText("demande " + i), t0.plusMinutes(i * 2L)));
            history.add(message("ASSISTANT", longText("réponse " + i), t0.plusMinutes(i * 2L + 1)));
        }
        return history;
    }

    /** Listener capteur : compte les transitions de compaction reçues. */
    private static final class CapturingListener implements AtelierProgressListener {
        int started;
        final List<Integer> doneWith = new ArrayList<>();

        @Override
        public void onAction(AtelierStepEvent step) {
            // rien
        }

        @Override
        public void onText(String text) {
            // rien
        }

        @Override
        public void onCompactionStarted() {
            started++;
        }

        @Override
        public void onCompactionDone(int summarizedTurns) {
            doneWith.add(summarizedTurns);
        }
    }

    @Test
    @DisplayName("compaction réelle → démarrée une fois, puis terminée avec N = tours USER résumés")
    void emitsStartThenDoneWithSummarizedTurns() {
        StubAiAgentProvider provider = new StubAiAgentProvider();
        provider.enqueueFinal("Résumé compact du travail passé.");
        stubHistory(threeExchanges());
        CapturingListener listener = new CapturingListener();

        AtelierCompactionService.CompactionOutcome outcome =
                service(provider).compactIfOversized(userId, workspace, null, listener);

        assertThat(outcome.compacted()).isTrue();
        assertThat(listener.started).isEqualTo(1);
        // 6 messages, on garde les 2 derniers → 4 anciens résumés = 2 tours USER.
        assertThat(listener.doneWith).containsExactly(2);
    }

    @Test
    @DisplayName("rien d'ancien à résumer → aucune transition (ni démarrée ni terminée)")
    void emitsNothingWhenNothingToSummarize() {
        StubAiAgentProvider provider = new StubAiAgentProvider();
        stubHistory(new ArrayList<>(List.of(
                message("USER", longText("demande"), OffsetDateTime.now()),
                message("ASSISTANT", longText("réponse"), OffsetDateTime.now()))));
        CapturingListener listener = new CapturingListener();

        service(provider).compactIfOversized(userId, workspace, null, listener);

        assertThat(listener.started).isZero();
        assertThat(listener.doneWith).isEmpty();
    }

    @Test
    @DisplayName("sous le seuil → aucune transition")
    void emitsNothingUnderThreshold() {
        StubAiAgentProvider provider = new StubAiAgentProvider();
        stubHistory(new ArrayList<>(List.of(
                message("USER", "petite demande", OffsetDateTime.now()),
                message("ASSISTANT", "petite réponse", OffsetDateTime.now()))));
        CapturingListener listener = new CapturingListener();

        service(provider).compactIfOversized(userId, workspace, null, listener);

        assertThat(listener.started).isZero();
        assertThat(listener.doneWith).isEmpty();
    }

    @Test
    @DisplayName("résumé blanc → démarrée émise, puis terminée avec N = 0 (barre retirée, pas de marqueur)")
    void emitsDoneZeroWhenSummaryIsBlank() {
        StubAiAgentProvider provider = new StubAiAgentProvider();
        provider.enqueueEmptyFinal();
        stubHistory(threeExchanges());
        CapturingListener listener = new CapturingListener();

        AtelierCompactionService.CompactionOutcome outcome =
                service(provider).compactIfOversized(userId, workspace, null, listener);

        assertThat(outcome.compacted()).isFalse();
        assertThat(listener.started).isEqualTo(1);
        assertThat(listener.doneWith).containsExactly(0);
    }

    @Test
    @DisplayName("appel de synthèse en échec → terminée avec N = 0, et la compaction ne lève pas (best-effort)")
    void emitsDoneZeroWhenProviderThrows() {
        AiAgentProvider provider = org.mockito.Mockito.mock(AiAgentProvider.class);
        when(provider.nextTurn(any())).thenThrow(new RuntimeException("fournisseur indisponible"));
        stubHistory(threeExchanges());
        CapturingListener listener = new CapturingListener();

        AtelierCompactionService.CompactionOutcome outcome =
                service(provider).compactIfOversized(userId, workspace, null, listener);

        assertThat(outcome.compacted()).isFalse();
        assertThat(listener.started).isEqualTo(1);
        assertThat(listener.doneWith).containsExactly(0);
    }

    @Test
    @DisplayName("best-effort : un listener qui LÈVE ne fait pas échouer la compaction")
    void listenerFailureNeverBreaksCompaction() {
        StubAiAgentProvider provider = new StubAiAgentProvider();
        provider.enqueueFinal("Résumé compact du travail passé.");
        stubHistory(threeExchanges());
        // Un écran défaillant : chaque transition lève.
        AtelierProgressListener hostile = new AtelierProgressListener() {
            @Override
            public void onAction(AtelierStepEvent step) {
                // rien
            }

            @Override
            public void onText(String text) {
                // rien
            }

            @Override
            public void onCompactionStarted() {
                throw new RuntimeException("écran cassé (démarrée)");
            }

            @Override
            public void onCompactionDone(int summarizedTurns) {
                throw new RuntimeException("écran cassé (terminée)");
            }
        };

        AtelierCompactionService.CompactionOutcome[] outcome = new AtelierCompactionService.CompactionOutcome[1];
        assertThatCode(() ->
                outcome[0] = service(provider).compactIfOversized(userId, workspace, null, hostile))
                .doesNotThrowAnyException();
        // La compaction a bien eu lieu malgré l'écran défaillant.
        assertThat(outcome[0].compacted()).isTrue();
        assertThat(workspace.getChatThreadSummary()).isEqualTo("Résumé compact du travail passé.");
    }
}
