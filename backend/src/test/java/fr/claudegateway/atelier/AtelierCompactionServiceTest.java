package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;

/**
 * Compaction automatique du fil d'Atelier (F-117 / SF-117-01) : quand le texte rejoué dépasse le
 * seuil, les tours anciens sont résumés et la frontière avancée — les tours récents restent entiers,
 * l'affichage n'est jamais touché.
 */
@ExtendWith(MockitoExtension.class)
class AtelierCompactionServiceTest {

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

    /** Service câblé sur un fournisseur donné, avec un seuil bas et 2 messages récents gardés. */
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

    /** Texte assez long pour franchir le seuil (100 tokens ≈ 400 caractères). */
    private String longText(String prefix) {
        return prefix + " " + "x".repeat(500);
    }

    private void stubHistory(List<AtelierMessage> history) {
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);
    }

    @Test
    void compactsAncientTurnsAndKeepsRecentOnesWholeWhenOverThreshold() {
        StubAiAgentProvider provider = new StubAiAgentProvider();
        provider.enqueueFinal("Résumé : objectif X, fichiers a.txt et b.txt.");
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(3);
        List<AtelierMessage> history = new ArrayList<>(List.of(
                message("USER", longText("demande 1"), t0),
                message("ASSISTANT", longText("réponse 1"), t0.plusMinutes(1)),
                message("USER", longText("demande 2"), t0.plusMinutes(2)),
                message("ASSISTANT", longText("réponse 2"), t0.plusMinutes(3))));
        stubHistory(history);

        AtelierCompactionService.CompactionOutcome outcome =
                service(provider).compactIfOversized(userId, workspace, null);

        assertThat(outcome.compacted()).isTrue();
        // 4 messages, on garde les 2 derniers entiers → 2 anciens résumés.
        assertThat(workspace.getChatThreadSummary())
                .isEqualTo("Résumé : objectif X, fichiers a.txt et b.txt.");
        // La frontière avance au premier message récent gardé (le 3e, index 2).
        assertThat(workspace.getChatThreadStartedAt()).isEqualTo(t0.plusMinutes(2));
        // Le fournisseur n'a vu QUE les tours anciens (aucun outil, appel dédié borné).
        AgentTurnRequest request = provider.lastRequest;
        assertThat(request.tools()).isEmpty();
        assertThat(request.system()).isEqualTo(AtelierCompactionService.SUMMARY_SYSTEM_PROMPT);
        verify(workspaceRepository).save(workspace);
    }

    @Test
    void doesNothingUnderThreshold() {
        StubAiAgentProvider provider = new StubAiAgentProvider();
        stubHistory(new ArrayList<>(List.of(
                message("USER", "petite demande", OffsetDateTime.now()),
                message("ASSISTANT", "petite réponse", OffsetDateTime.now()))));

        AtelierCompactionService.CompactionOutcome outcome =
                service(provider).compactIfOversized(userId, workspace, null);

        assertThat(outcome.compacted()).isFalse();
        assertThat(workspace.getChatThreadSummary()).isNull();
        assertThat(provider.lastRequest).isNull();
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void doesNothingWhenNothingIsOldEnoughToSummarize() {
        // Deux messages, on garde les 2 derniers : rien d'ancien à résumer, même au-dessus du seuil.
        StubAiAgentProvider provider = new StubAiAgentProvider();
        stubHistory(new ArrayList<>(List.of(
                message("USER", longText("demande"), OffsetDateTime.now()),
                message("ASSISTANT", longText("réponse"), OffsetDateTime.now()))));

        AtelierCompactionService.CompactionOutcome outcome =
                service(provider).compactIfOversized(userId, workspace, null);

        assertThat(outcome.compacted()).isFalse();
        assertThat(provider.lastRequest).isNull();
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void bestEffortLeavesFilUntouchedWhenSummaryCallFails() {
        AiAgentProvider provider = org.mockito.Mockito.mock(AiAgentProvider.class);
        when(provider.nextTurn(any())).thenThrow(new RuntimeException("fournisseur indisponible"));
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(1);
        stubHistory(new ArrayList<>(List.of(
                message("USER", longText("d1"), t0),
                message("ASSISTANT", longText("r1"), t0.plusMinutes(1)),
                message("USER", longText("d2"), t0.plusMinutes(2)),
                message("ASSISTANT", longText("r2"), t0.plusMinutes(3)))));

        AtelierCompactionService.CompactionOutcome outcome =
                service(provider).compactIfOversized(userId, workspace, null);

        assertThat(outcome.compacted()).isFalse();
        assertThat(workspace.getChatThreadSummary()).isNull();
        assertThat(workspace.getChatThreadStartedAt()).isNull();
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void disabledDoesNothing() {
        StubAiAgentProvider provider = new StubAiAgentProvider();
        AtelierCompactionProperties disabled = new AtelierCompactionProperties(false, 100, 2);
        AtelierProperties atelier = new AtelierProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, null, true);
        AtelierCompactionService service = new AtelierCompactionService(messageRepository,
                workspaceRepository, provider, disabled, atelier);

        AtelierCompactionService.CompactionOutcome outcome =
                service.compactIfOversized(userId, workspace, null);

        assertThat(outcome.compacted()).isFalse();
        assertThat(provider.lastRequest).isNull();
        verify(messageRepository, never()).findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(any(), any());
    }

    @Test
    void summaryIsIncrementalOverThePreviousSummary() {
        StubAiAgentProvider provider = new StubAiAgentProvider();
        provider.enqueueFinal("Nouveau résumé.");
        workspace.setChatThreadSummary("Résumé précédent important.");
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(2);
        workspace.setChatThreadStartedAt(t0);
        when(messageRepository.findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                workspaceId, userId, t0)).thenReturn(new ArrayList<>(List.of(
                        message("USER", longText("d1"), t0),
                        message("ASSISTANT", longText("r1"), t0.plusMinutes(1)),
                        message("USER", longText("d2"), t0.plusMinutes(2)),
                        message("ASSISTANT", longText("r2"), t0.plusMinutes(3)))));

        AtelierCompactionService.CompactionOutcome outcome =
                service(provider).compactIfOversized(userId, workspace, null);

        assertThat(outcome.compacted()).isTrue();
        assertThat(workspace.getChatThreadSummary()).isEqualTo("Nouveau résumé.");
        // Le texte soumis au résumé porte l'ancien résumé (compaction incrémentale).
        String submitted = provider.lastRequest.messages().get(0).content().get(0).toString();
        assertThat(submitted).contains("Résumé précédent important.");
    }

    @Test
    void summaryPrefixInjectsMarkerWhenPresent() {
        assertThat(AtelierCompactionService.summaryPrefix(workspace)).isNull();
        workspace.setChatThreadSummary("un résumé");
        var prefix = AtelierCompactionService.summaryPrefix(workspace);
        assertThat(prefix).isNotNull();
        assertThat(prefix.content().get(0).toString()).contains(AtelierCompactionService.SUMMARY_MARKER);
    }
}
