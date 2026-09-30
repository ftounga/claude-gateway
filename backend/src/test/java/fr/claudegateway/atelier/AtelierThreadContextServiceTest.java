package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.dto.AtelierResumeResponse;
import fr.claudegateway.atelier.dto.ThreadContextSummaryResponse;
import fr.claudegateway.atelier.recall.AtelierSemanticRecall;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;

/**
 * L'état mémoire du fil (F-165 / SF-165-03) : contexte vivant (proxy du dernier tour), progression vers
 * le seuil de compaction, part vivante vs rangée, présence du résumé ancré, état du rappel — et, surtout,
 * l'<b>isolation</b> : le fil d'autrui ne rend jamais un état (404 propagé, aucune lecture du journal).
 */
@ExtendWith(MockitoExtension.class)
class AtelierThreadContextServiceTest {

    @Mock private AtelierThreadService threadService;
    @Mock private UsageTurnRepository usageTurnRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private AtelierSemanticRecall semanticRecall;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final OffsetDateTime threadStart = OffsetDateTime.parse("2026-09-30T10:00:00Z");

    private AtelierThreadContextService service(AtelierCompactionProperties props) {
        return new AtelierThreadContextService(threadService, usageTurnRepository, workspaceRepository,
                props, semanticRecall);
    }

    private AtelierResumeResponse resume(OffsetDateTime startedAt, int liveTurns, int foldedTurns) {
        return new AtelierResumeResponse(liveTurns, null, startedAt, foldedTurns, "NONE", "ACT",
                List.of(), "AUCUN", null);
    }

    private UsageTurn turn(OffsetDateTime at, long input) {
        return UsageTurn.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .inputTokens(input)
                .cacheReadTokens(0)
                .cacheWriteTokens(0)
                .outputTokens(0)
                .model("claude-opus-5")
                .occurredAt(at)
                .build();
    }

    private void anchoredSummary(String summary) {
        Workspace workspace = mock(Workspace.class);
        lenient().when(workspace.getChatThreadSummary()).thenReturn(summary);
        lenient().when(workspaceRepository.findByIdAndUserId(workspaceId, userId))
                .thenReturn(Optional.of(workspace));
    }

    @Test
    void composeLEtatMemoireEtIsoleUserEtWorkspace() {
        when(threadService.resumeState(userId, workspaceId)).thenReturn(resume(threadStart, 3, 5));
        // Un tour AVANT la frontière (ignoré) et un tour APRÈS (le dernier du fil = contexte vivant).
        UsageTurn before = turn(threadStart.minusMinutes(5), 999_999);
        UsageTurn after = turn(threadStart.plusMinutes(1), 60_000);
        when(usageTurnRepository.findByUserIdAndWorkspaceIdOrderByOccurredAtAsc(userId, workspaceId))
                .thenReturn(List.of(before, after));
        anchoredSummary("résumé ancré du fil (tour 12)");
        when(semanticRecall.isEnabled()).thenReturn(true);

        ThreadContextSummaryResponse summary =
                service(new AtelierCompactionProperties(true, 120_000, 6)).summary(userId, workspaceId);

        // La lecture du journal est bornée EXACTEMENT à (userId, workspaceId) — isolation par construction.
        verify(usageTurnRepository)
                .findByUserIdAndWorkspaceIdOrderByOccurredAtAsc(userId, workspaceId);
        verify(workspaceRepository).findByIdAndUserId(workspaceId, userId);

        // Contexte vivant = entrée du dernier tour du fil courant ; « pages » ≈ 60 000 / 500 = 120.
        assertThat(summary.contextTokens()).isEqualTo(60_000L);
        assertThat(summary.contextPages()).isEqualTo(120);
        // Progression vers le seuil : 60 000 / 120 000 = 50 %.
        assertThat(summary.fillPercent()).isEqualTo(50);
        assertThat(summary.triggerTokens()).isEqualTo(120_000);
        assertThat(summary.triggerPages()).isEqualTo(240);
        // Vivants / rangés viennent de /resume.
        assertThat(summary.liveTurns()).isEqualTo(3);
        assertThat(summary.foldedTurns()).isEqualTo(5);
        // Résumé ancré présent, compaction active, rappel sémantique actif.
        assertThat(summary.hasAnchoredSummary()).isTrue();
        assertThat(summary.compactionEnabled()).isTrue();
        assertThat(summary.keepRecentTurns()).isEqualTo(6);
        assertThat(summary.recallSemantic()).isTrue();
    }

    @Test
    void filVideRendDesZerosSansErreur() {
        when(threadService.resumeState(userId, workspaceId)).thenReturn(resume(null, 0, 0));
        when(usageTurnRepository.findByUserIdAndWorkspaceIdOrderByOccurredAtAsc(userId, workspaceId))
                .thenReturn(List.of());
        anchoredSummary(null);

        ThreadContextSummaryResponse summary =
                service(new AtelierCompactionProperties(true, 120_000, 6)).summary(userId, workspaceId);

        assertThat(summary.contextTokens()).isZero();
        assertThat(summary.contextPages()).isZero();
        assertThat(summary.fillPercent()).isZero();
        assertThat(summary.hasAnchoredSummary()).isFalse();
    }

    @Test
    void resumeAncreVideNeCompterPasCommePresent() {
        when(threadService.resumeState(userId, workspaceId)).thenReturn(resume(null, 1, 0));
        when(usageTurnRepository.findByUserIdAndWorkspaceIdOrderByOccurredAtAsc(userId, workspaceId))
                .thenReturn(List.of(turn(threadStart, 10_000)));
        anchoredSummary("   "); // blanc : pas un résumé

        ThreadContextSummaryResponse summary =
                service(new AtelierCompactionProperties(true, 120_000, 6)).summary(userId, workspaceId);

        assertThat(summary.hasAnchoredSummary()).isFalse();
    }

    @Test
    void seuilIncoherentRetombeSurLeDefaut() {
        when(threadService.resumeState(userId, workspaceId)).thenReturn(resume(null, 1, 0));
        when(usageTurnRepository.findByUserIdAndWorkspaceIdOrderByOccurredAtAsc(userId, workspaceId))
                .thenReturn(List.of(turn(threadStart, 60_000)));
        anchoredSummary(null);

        // enabled null → true ; trigger 0 → défaut 120 000 ; keep 1 (< plancher) → défaut 6.
        ThreadContextSummaryResponse summary =
                service(new AtelierCompactionProperties(null, 0, 1)).summary(userId, workspaceId);

        assertThat(summary.compactionEnabled()).isTrue();
        assertThat(summary.triggerTokens()).isEqualTo(AtelierCompactionProperties.DEFAULT_TRIGGER_TOKENS);
        assertThat(summary.keepRecentTurns()).isEqualTo(AtelierCompactionProperties.DEFAULT_KEEP_RECENT_TURNS);
        assertThat(summary.fillPercent()).isEqualTo(50);
    }

    @Test
    void unFilDAutruiNeRendJamaisUnEtat() {
        // requireOwned (dans resumeState) refuse : 404 indiscernable, jamais un état.
        when(threadService.resumeState(userId, workspaceId))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable"));

        assertThatThrownBy(() ->
                service(new AtelierCompactionProperties(true, 120_000, 6)).summary(userId, workspaceId))
                .isInstanceOf(WorkspaceNotFoundException.class);

        // Aucune lecture du journal ni du workspace : rien de l'état d'autrui n'est même lu.
        verify(usageTurnRepository, never())
                .findByUserIdAndWorkspaceIdOrderByOccurredAtAsc(userId, workspaceId);
        verify(workspaceRepository, never()).findByIdAndUserId(workspaceId, userId);
    }
}
