package fr.claudegateway.bilan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.quota.ProviderPricingProperties;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;
import fr.claudegateway.runner.audit.RunnerAudit;
import fr.claudegateway.runner.audit.RunnerAuditRepository;

/**
 * Le relevé d'une session (F-155 / SF-155-01).
 *
 * <p>Ce que ces tests tiennent : les chiffres se vérifient <b>sans appeler un modèle</b>, la part du
 * cache est le chiffre de F-134, les classements sont bornés et honnêtes, un relevé vide est valide,
 * et le projet d'un autre est introuvable.</p>
 */
class SessionLedgerServiceTest {

    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final UsageTurnRepository usageTurns = mock(UsageTurnRepository.class);
    private final RunnerAuditRepository runnerAudit = mock(RunnerAuditRepository.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final OffsetDateTime from = OffsetDateTime.parse("2026-09-24T08:00:00Z");
    private final OffsetDateTime to = OffsetDateTime.parse("2026-09-24T12:00:00Z");

    private SessionLedgerService service;

    @BeforeEach
    void setUp() {
        // Taux d'affichage explicite : le relevé ne doit JAMAIS porter un taux en dur.
        ProviderPricingProperties pricing = new ProviderPricingProperties(
                null, null, null, null, null, new BigDecimal("0.50"));
        service = new SessionLedgerService(workspaces, usageTurns, runnerAudit, pricing);
        when(workspaces.requireOwned(userId, workspaceId)).thenReturn(new Workspace());
    }

    private UsageTurn turn(String at, String costUsd, long input, long output, long cacheRead) {
        return UsageTurn.builder()
                .userId(userId).workspaceId(workspaceId)
                .occurredAt(OffsetDateTime.parse(at))
                .model("claude-opus-5")
                .providerCostUsd(costUsd == null ? null : new BigDecimal(costUsd))
                .inputTokens(input).outputTokens(output)
                .cacheReadTokens(cacheRead).cacheWriteTokens(0)
                .build();
    }

    private RunnerAudit call(String at, String tool, String target, long ms, String outcome) {
        return RunnerAudit.builder()
                .userId(userId).workspaceId(workspaceId)
                .createdAt(OffsetDateTime.parse(at))
                .callId("c-" + at).tool(tool).target(target)
                .durationMs(ms).outcome(outcome)
                .build();
    }

    private void given(List<UsageTurn> turns, List<RunnerAudit> calls) {
        when(usageTurns.findByUserIdAndWorkspaceIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                userId, workspaceId, from, to)).thenReturn(turns);
        when(runnerAudit.findByUserIdAndWorkspaceIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                userId, workspaceId, from, to)).thenReturn(calls);
    }

    @Test
    @DisplayName("compte les tours, la durée RÉELLE, les outils, les échecs et les fichiers DISTINCTS")
    void countsWhatWasDone() {
        given(List.of(turn("2026-09-24T09:00:00Z", "1.00", 1000, 200, 0),
                      turn("2026-09-24T10:30:00Z", "2.00", 1000, 200, 0)),
              List.of(call("2026-09-24T09:05:00Z", "write_file", "src/App.java", 100, "OK"),
                      call("2026-09-24T09:06:00Z", "write_file", "src/App.java", 100, "OK"),
                      call("2026-09-24T09:07:00Z", "edit_file", "README.md", 100, "OK"),
                      call("2026-09-24T09:08:00Z", "bash", "mvn test", 4000, "ERROR")));

        SessionLedger ledger = service.of(userId, workspaceId, from, to);

        assertThat(ledger.turns()).isEqualTo(2);
        assertThat(ledger.elapsed()).as("du premier au dernier événement, pas la somme des durées")
                .isEqualTo(Duration.ofMinutes(90));
        assertThat(ledger.toolCalls()).isEqualTo(4);
        assertThat(ledger.failedTools()).isEqualTo(1);
        assertThat(ledger.filesWritten()).as("deux écritures du même fichier font UN fichier")
                .isEqualTo(2);
        assertThat(ledger.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("convertit au taux CONFIGURÉ, jamais à un taux en dur")
    void convertsAtTheConfiguredRate() {
        given(List.of(turn("2026-09-24T09:00:00Z", "10.00", 100, 10, 0)), List.of());

        assertThat(service.of(userId, workspaceId, from, to).costEur())
                .isEqualByComparingTo("5.00"); // 10 USD × 0,50
    }

    @Test
    @DisplayName("la part du cache est le chiffre de F-134 : ce qui dit si la consigne est repayée")
    void theCacheShareIsTheF134Number() {
        // SF-155-06 : `input` est le volume TRAITÉ — il CONTIENT le cache lu. Un jeu de données où
        // le cache dépasse l'entrée est impossible en production ; l'ancien encodait la sémantique
        // fausse que cette subfeature corrige.
        given(List.of(turn("2026-09-24T09:00:00Z", "1.00", 10_000, 100, 9_000)), List.of());

        SessionLedger ledger = service.of(userId, workspaceId, from, to);
        assertThat(ledger.cacheReadTokens()).isEqualTo(9000);
        assertThat(ledger.cacheShare()).isEqualTo(90);
    }

    @Test
    @DisplayName("une entrée nulle ne fait pas une division par zéro : la part vaut 0")
    void noTokensMeansNoShare() {
        given(List.of(), List.of(call("2026-09-24T09:00:00Z", "bash", "ls", 10, "OK")));
        assertThat(service.of(userId, workspaceId, from, to).cacheShare()).isZero();
    }

    @Test
    @DisplayName("les tours SANS coût connu sont comptés à part — sinon le total paraîtrait faux")
    void turnsWithoutAKnownCostAreCountedApart() {
        given(List.of(turn("2026-09-24T09:00:00Z", "2.00", 100, 10, 0),
                      turn("2026-09-24T09:10:00Z", null, 100, 10, 0)), List.of());

        SessionLedger ledger = service.of(userId, workspaceId, from, to);
        assertThat(ledger.turns()).isEqualTo(2);
        assertThat(ledger.turnsWithoutCost()).isEqualTo(1);
        assertThat(ledger.costEur()).isEqualByComparingTo("1.00");
        assertThat(ledger.costliestTurns()).hasSize(1); // celui sans coût n'est pas classable
    }

    @Test
    @DisplayName("classe les 3 tours les plus chers, du plus cher au moins cher")
    void ranksTheThreeCostliestTurns() {
        given(List.of(turn("2026-09-24T09:00:00Z", "1.00", 10, 1, 0),
                      turn("2026-09-24T09:10:00Z", "9.00", 10, 1, 0),
                      turn("2026-09-24T09:20:00Z", "5.00", 10, 1, 0),
                      turn("2026-09-24T09:30:00Z", "3.00", 10, 1, 0)), List.of());

        assertThat(service.of(userId, workspaceId, from, to).costliestTurns())
                .extracting(SessionLedger.CostlyTurn::costEur)
                .containsExactly(new BigDecimal("4.50"), new BigDecimal("2.50"), new BigDecimal("1.50"));
    }

    @Test
    @DisplayName("classe les outils par DURÉE cumulée : cent lectures pèsent moins qu'une commande longue")
    void ranksToolsByAccumulatedTime() {
        given(List.of(), List.of(
                call("2026-09-24T09:00:00Z", "read_file", "a", 10, "OK"),
                call("2026-09-24T09:00:01Z", "read_file", "b", 10, "OK"),
                call("2026-09-24T09:00:02Z", "read_file", "c", 10, "OK"),
                call("2026-09-24T09:01:00Z", "bash", "mvn test", 240_000, "OK"),
                call("2026-09-24T09:05:00Z", "grep", "TODO", 500, "ERROR")));

        List<SessionLedger.HeavyTool> tools = service.of(userId, workspaceId, from, to).heaviestTools();

        assertThat(tools).extracting(SessionLedger.HeavyTool::tool)
                .containsExactly("bash", "grep", "read_file");
        assertThat(tools.get(0).total()).isEqualTo(Duration.ofMinutes(4));
        assertThat(tools.get(1).failures()).isEqualTo(1);
        assertThat(tools.get(2).calls()).isEqualTo(3);
    }

    @Test
    @DisplayName("les classements sont BORNÉS à trois — un classement, pas un inventaire")
    void rankingsAreBounded() {
        List<UsageTurn> many = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> turn("2026-09-24T09:00:0" + (i % 10) + "Z", String.valueOf(i + 1), 1, 1, 0))
                .toList();
        List<RunnerAudit> tools = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> call("2026-09-24T09:00:0" + (i % 10) + "Z", "outil" + i, "x", i, "OK"))
                .toList();
        given(many, tools);

        SessionLedger ledger = service.of(userId, workspaceId, from, to);
        assertThat(ledger.costliestTurns()).hasSize(SessionLedgerService.TOP);
        assertThat(ledger.heaviestTools()).hasSize(SessionLedgerService.TOP);
    }

    @Test
    @DisplayName("une session sans activité rend un relevé VIDE, pas une erreur")
    void anEmptySessionIsValid() {
        given(List.of(), List.of());
        assertThat(service.of(userId, workspaceId, from, to).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("une fenêtre inversée ou incomplète rend un relevé vide, et ne lit rien")
    void anInvertedWindowReadsNothing() {
        assertThat(service.of(userId, workspaceId, to, from).isEmpty()).isTrue();
        assertThat(service.of(userId, workspaceId, null, to).isEmpty()).isTrue();
        verify(usageTurns, never())
                .findByUserIdAndWorkspaceIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any(), any());
    }

    @Test
    @DisplayName("ISOLATION — le projet d'un autre compte est introuvable, et rien n'est lu")
    void anotherAccountSeesNothing() {
        UUID intruder = UUID.randomUUID();
        when(workspaces.requireOwned(intruder, workspaceId))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable"));

        assertThatThrownBy(() -> service.of(intruder, workspaceId, from, to))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verify(usageTurns, never())
                .findByUserIdAndWorkspaceIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any(), any());
        verify(runnerAudit, never())
                .findByUserIdAndWorkspaceIdAndCreatedAtBetweenOrderByCreatedAtAsc(any(), any(), any(), any());
    }
}
