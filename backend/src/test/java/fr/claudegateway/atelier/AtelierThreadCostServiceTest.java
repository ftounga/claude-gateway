package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.atelier.dto.AtelierResumeResponse;
import fr.claudegateway.atelier.dto.ThreadCostSummaryResponse;
import fr.claudegateway.quota.ProviderPricingProperties;
import fr.claudegateway.quota.TurnCostView;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;

/**
 * L'économie du fil (F-165 / SF-165-02) : la décomposition écriture/lecture/sortie, le cache chaud, le
 * contexte vivant et la mini-tendance — et, surtout, l'<b>isolation</b> : un fil d'autrui ne rend jamais
 * un coût. Tarifs et conversion sont les VRAIS (grille par défaut, taux 0,92) : le calcul est vérifié de
 * bout en bout, pas simulé.
 */
@ExtendWith(MockitoExtension.class)
class AtelierThreadCostServiceTest {

    @Mock private AtelierThreadService threadService;
    @Mock private UsageTurnRepository usageTurnRepository;
    @Mock private AdminService adminService;

    private AtelierThreadCostService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final OffsetDateTime threadStart = OffsetDateTime.parse("2026-09-30T10:00:00Z");

    @BeforeEach
    void setUp() {
        // Grille et conversion réelles : usd-to-eur = 0,92, opus-5 = 5 / 25 / 0,50 / 10 par Mtoken.
        ProviderPricingProperties pricing =
                new ProviderPricingProperties(null, null, null, null, null, null);
        TurnCostView costView = new TurnCostView(adminService, pricing);
        service = new AtelierThreadCostService(threadService, usageTurnRepository, pricing, costView);
    }

    private AtelierResumeResponse resume(OffsetDateTime startedAt, int liveTurns, int foldedTurns) {
        return new AtelierResumeResponse(liveTurns, null, startedAt, foldedTurns, "NONE", "ACT",
                List.of(), "AUCUN", null);
    }

    private UsageTurn turn(OffsetDateTime at, long input, long cacheRead, long cacheWrite, long output,
            String costUsd) {
        return UsageTurn.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .inputTokens(input)
                .cacheReadTokens(cacheRead)
                .cacheWriteTokens(cacheWrite)
                .outputTokens(output)
                .model("claude-opus-5")
                .providerCostUsd(costUsd == null ? null : new BigDecimal(costUsd))
                .occurredAt(at)
                .build();
    }

    @Test
    void decomposeLeFilCourantEtIsoleUserEtWorkspace() {
        when(threadService.resumeState(userId, workspaceId))
                .thenReturn(resume(threadStart, 3, 5));
        // Un tour AVANT la frontière (à ne PAS compter) et un tour APRÈS.
        UsageTurn before = turn(threadStart.minusMinutes(5), 999_999, 0, 0, 999_999, "99.00");
        // input inclut le cache : 100 000 = 2 000 neuf + 90 000 relu + 8 000 écrit.
        UsageTurn after = turn(threadStart.plusMinutes(1), 100_000, 90_000, 8_000, 2_000, "0.50");
        when(usageTurnRepository.findByUserIdAndWorkspaceIdOrderByOccurredAtAsc(userId, workspaceId))
                .thenReturn(List.of(before, after));

        ThreadCostSummaryResponse summary = service.summary(userId, workspaceId);

        // La lecture est bornée EXACTEMENT à (userId, workspaceId) — isolation par construction.
        verify(usageTurnRepository)
                .findByUserIdAndWorkspaceIdOrderByOccurredAtAsc(userId, workspaceId);

        assertThat(summary.currency()).isEqualTo("EUR");
        // Le tour d'avant la frontière est EXCLU : cumulatif = 0,50 $ × 0,92 = 0,46 € (et non ~92 €).
        assertThat(summary.turnCount()).isEqualTo(1);
        assertThat(summary.cumulativeEur()).isEqualByComparingTo("0.46");
        assertThat(summary.lastTurnEur()).isEqualByComparingTo("0.46");

        // Décomposition : écriture 0,09 $, lecture 0,045 $, sortie 0,05 $ (total 0,185 $).
        ThreadCostSummaryResponse.Breakdown breakdown = summary.breakdown();
        assertThat(breakdown.writePercent()).isEqualTo(49);
        assertThat(breakdown.readPercent()).isEqualTo(24);
        assertThat(breakdown.outputPercent()).isEqualTo(27);
        // Les trois postes font TOUJOURS 100 %.
        assertThat(breakdown.writePercent() + breakdown.readPercent() + breakdown.outputPercent())
                .isEqualTo(100);
        assertThat(breakdown.writeEur()).isEqualByComparingTo("0.08"); // 0,09 × 0,92 ≈ 0,08
        assertThat(breakdown.readEur()).isEqualByComparingTo("0.04");
        assertThat(breakdown.outputEur()).isEqualByComparingTo("0.05");

        // Cache chaud = relu / entrée traitée = 90 000 / 100 000 = 90 %.
        assertThat(summary.hotCachePercent()).isEqualTo(90);
        // Contexte vivant = entrée du dernier tour ; « pages » ≈ 100 000 / 500 = 200.
        assertThat(summary.contextTokens()).isEqualTo(100_000L);
        assertThat(summary.contextPages()).isEqualTo(200);
        // Tours vivants / rangés viennent de /resume.
        assertThat(summary.liveTurns()).isEqualTo(3);
        assertThat(summary.foldedTurns()).isEqualTo(5);
        // Mini-tendance : un point (le seul tour du fil).
        assertThat(summary.trendEur()).hasSize(1);
        assertThat(summary.trendEur().get(0)).isEqualByComparingTo("0.46");
    }

    @Test
    void filVideRendDesZerosSansErreur() {
        when(threadService.resumeState(userId, workspaceId)).thenReturn(resume(null, 0, 0));
        when(usageTurnRepository.findByUserIdAndWorkspaceIdOrderByOccurredAtAsc(userId, workspaceId))
                .thenReturn(List.of());

        ThreadCostSummaryResponse summary = service.summary(userId, workspaceId);

        assertThat(summary.turnCount()).isZero();
        assertThat(summary.cumulativeEur()).isEqualByComparingTo("0.00");
        assertThat(summary.lastTurnEur()).isEqualByComparingTo("0.00");
        assertThat(summary.hotCachePercent()).isZero();
        assertThat(summary.contextTokens()).isZero();
        assertThat(summary.contextPages()).isZero();
        assertThat(summary.breakdown().writePercent()).isZero();
        assertThat(summary.breakdown().readPercent()).isZero();
        assertThat(summary.breakdown().outputPercent()).isZero();
        assertThat(summary.trendEur()).isEmpty();
    }

    @Test
    void toursSansCoutComptentPourZeroEuro() {
        when(threadService.resumeState(userId, workspaceId)).thenReturn(resume(null, 1, 0));
        // Tour antérieur à F-133 : aucun provider_cost_usd — compté 0 €, pas d'estimation inventée.
        UsageTurn legacy = turn(threadStart, 10_000, 0, 0, 500, null);
        when(usageTurnRepository.findByUserIdAndWorkspaceIdOrderByOccurredAtAsc(userId, workspaceId))
                .thenReturn(List.of(legacy));

        ThreadCostSummaryResponse summary = service.summary(userId, workspaceId);

        assertThat(summary.turnCount()).isEqualTo(1);
        assertThat(summary.cumulativeEur()).isEqualByComparingTo("0.00");
        // Le contexte reste lisible même sans coût enregistré.
        assertThat(summary.contextTokens()).isEqualTo(10_000L);
    }

    @Test
    void unFilDAutruiNeRendJamaisUnCout() {
        // requireOwned (dans resumeState) refuse : 404 indiscernable, jamais un montant.
        when(threadService.resumeState(userId, workspaceId))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable"));

        assertThatThrownBy(() -> service.summary(userId, workspaceId))
                .isInstanceOf(WorkspaceNotFoundException.class);

        // Aucune lecture du journal de consommation n'a eu lieu : rien du coût d'autrui n'est même lu.
        verify(usageTurnRepository, never())
                .findByUserIdAndWorkspaceIdOrderByOccurredAtAsc(userId, workspaceId);
    }
}
