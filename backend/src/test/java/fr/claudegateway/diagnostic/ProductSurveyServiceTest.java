package fr.claudegateway.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.quota.ProviderPricingProperties;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;
import fr.claudegateway.runner.audit.RunnerAudit;
import fr.claudegateway.runner.audit.RunnerAuditRepository;

/**
 * L'enquête sur une période (F-156 / SF-156-02).
 *
 * <p>Ce que ces tests tiennent : le <b>dénominateur</b> sans lequel aucun gain n'est rapportable, une
 * capacité <b>jamais déclenchée</b> rendue comme un résultat et non comme un vide, un montant
 * calculé <b>uniquement</b> quand il se calcule, et une période bornée qui le dit.</p>
 */
class ProductSurveyServiceTest {

    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-09-17T00:00:00Z");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-09-24T00:00:00Z");

    /** 15 $ le million en entrée, 1,50 $ lu en cache. 1 USD = 1 EUR : les chiffres restent lisibles. */
    private static final ProviderPricingProperties PRICING = new ProviderPricingProperties(
            "2026-09-20", "claude-opus-5",
            Map.of("claude-opus-5", new ProviderPricingProperties.ModelPricing(
                    new BigDecimal("15.00"), new BigDecimal("75.00"),
                    new BigDecimal("1.50"), new BigDecimal("18.75"))),
            null, null, new BigDecimal("1.00"));

    private final UsageTurnRepository usageTurns = mock(UsageTurnRepository.class);
    private final RunnerAuditRepository runnerAudit = mock(RunnerAuditRepository.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID projectA = UUID.randomUUID();
    private final UUID projectB = UUID.randomUUID();

    private ProductSurveyService service;

    @BeforeEach
    void setUp() {
        service = new ProductSurveyService(usageTurns, runnerAudit, PRICING);
    }

    private UsageTurn turn(UUID workspace, String costUsd, long input, long cacheRead) {
        return UsageTurn.builder().userId(userId).workspaceId(workspace)
                .occurredAt(FROM.plusHours(1)).model("claude-opus-5")
                .providerCostUsd(new BigDecimal(costUsd))
                .inputTokens(input).outputTokens(100).cacheReadTokens(cacheRead).cacheWriteTokens(0)
                .build();
    }

    private RunnerAudit call(UUID workspace, String tool) {
        return RunnerAudit.builder().userId(userId).workspaceId(workspace)
                .createdAt(FROM.plusHours(1)).callId("c").tool(tool).outcome("OK").build();
    }

    private void given(List<UsageTurn> turns, List<RunnerAudit> calls) {
        when(usageTurns.findByUserIdAndOccurredAtBetweenOrderByOccurredAtAsc(eq(userId), any(), eq(TO)))
                .thenReturn(turns);
        when(runnerAudit.findByUserIdAndCreatedAtBetweenOrderByCreatedAtAsc(eq(userId), any(), eq(TO)))
                .thenReturn(calls);
    }

    private CapabilityObservation observation(ProductSurvey survey, String id) {
        return survey.observations().stream()
                .filter(o -> o.capabilityId().equals(id))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("le DÉNOMINATEUR d'abord : tours, projets distincts, euros de la période")
    void theDenominatorComesFirst() {
        given(List.of(turn(projectA, "3.00", 1000, 9000), turn(projectB, "2.00", 1000, 9000)),
              List.of(call(projectA, "bash")));

        ProductSurvey survey = service.survey(userId, FROM, TO);

        assertThat(survey.turns()).isEqualTo(2);
        assertThat(survey.projects()).isEqualTo(2);
        assertThat(survey.costEur()).isEqualByComparingTo("5.00");
        assertThat(survey.truncated()).isFalse();
    }

    @Test
    @DisplayName("une capacité JAMAIS déclenchée est un RÉSULTAT, pas une absence de résultat")
    void aCapabilityThatNeverFiredIsAResult() {
        given(List.of(turn(projectA, "3.00", 1000, 9000)), List.of(call(projectA, "bash")));

        ProductSurvey survey = service.survey(userId, FROM, TO);

        CapabilityObservation plan = observation(survey, "plan");
        assertThat(plan.hits()).isZero();
        assertThat(plan.measurable()).isTrue();
        assertThat(plan.neverFired()).as("c'est le cas le plus intéressant du diagnostic").isTrue();
        assertThat(survey.observations())
                .as("toutes les capacités de la carte sont rendues, même muettes")
                .hasSameSizeAs(CapabilityMap.capabilities());
    }

    @Test
    @DisplayName("un signal d'OUTIL est compté, et les projets concernés distingués des observés")
    void toolSignalsAreCounted() {
        given(List.of(turn(projectA, "1.00", 1000, 9000), turn(projectB, "1.00", 1000, 9000)),
              List.of(call(projectA, "set_plan"), call(projectA, "set_plan")));

        CapabilityObservation plan = observation(service.survey(userId, FROM, TO), "plan");

        assertThat(plan.hits()).isEqualTo(2);
        assertThat(plan.projectsWithSignal()).isEqualTo(1);
        assertThat(plan.projectsObserved()).isEqualTo(2);
    }

    @Test
    @DisplayName("le cache froid se chiffre sur la grille réelle, tour par tour")
    void coldCacheIsPricedOnTheRealGrid() {
        // Un tour à 1 000 000 jetons d'entrée, 0 % de cache : viser 90 % déplace 900 000 jetons
        // de 15 $ à 1,50 $ le million → 12,15 €.
        given(List.of(turn(projectA, "40.00", 1_000_000, 0)), List.of());

        CapabilityObservation cache = observation(service.survey(userId, FROM, TO), "cache-de-prompt");

        assertThat(cache.wasteEur()).isEqualByComparingTo("12.15");
        assertThat(cache.hits()).as("aucun tour n'a lu le cache").isZero();
    }

    @Test
    @DisplayName("un cache déjà chaud ne coûte rien : aucun manque à gagner")
    void aWarmCacheWastesNothing() {
        given(List.of(turn(projectA, "5.00", 50_000, 950_000)), List.of());

        CapabilityObservation cache = observation(service.survey(userId, FROM, TO), "cache-de-prompt");

        assertThat(cache.wasteEur()).isEqualByComparingTo("0.00");
        assertThat(cache.hits()).isEqualTo(1);
    }

    @Test
    @DisplayName("un signal de TABLE n'invente aucun montant : le verdict attend SF-156-03")
    void tableSignalsInventNothing() {
        given(List.of(turn(projectA, "1.00", 1000, 9000)), List.of());

        CapabilityObservation memory = observation(service.survey(userId, FROM, TO),
                "memoire-de-resolutions");

        assertThat(memory.measurable()).isFalse();
        assertThat(memory.wasteEur()).isNull();
        assertThat(memory.neverFired())
                .as("non mesurable ici : on ne conclut pas qu'elle ne s'est pas déclenchée")
                .isFalse();
    }

    @Test
    @DisplayName("période vide, inversée ou incomplète : enquête vide, et AUCUNE lecture")
    void anInvalidPeriodReadsNothing() {
        assertThat(service.survey(userId, TO, FROM).isEmpty()).isTrue();
        assertThat(service.survey(userId, null, TO).isEmpty()).isTrue();
        assertThat(service.survey(userId, FROM, FROM).isEmpty()).isTrue();

        verify(usageTurns, never())
                .findByUserIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any());
        verify(runnerAudit, never())
                .findByUserIdAndCreatedAtBetweenOrderByCreatedAtAsc(any(), any(), any());
    }

    @Test
    @DisplayName("une période trop longue est ramenée à la borne, ET LE DIT")
    void anOverlongPeriodIsTruncatedAndSaysSo() {
        OffsetDateTime farBack = TO.minusDays(120);
        when(usageTurns.findByUserIdAndOccurredAtBetweenOrderByOccurredAtAsc(eq(userId), any(), eq(TO)))
                .thenReturn(List.of(turn(projectA, "1.00", 1000, 9000)));
        when(runnerAudit.findByUserIdAndCreatedAtBetweenOrderByCreatedAtAsc(eq(userId), any(), eq(TO)))
                .thenReturn(List.of());

        ProductSurvey survey = service.survey(userId, farBack, TO);

        assertThat(survey.truncated()).isTrue();
        assertThat(survey.from()).isEqualTo(TO.minus(ProductSurveyService.MAX_PERIOD));
    }

    @Test
    @DisplayName("une période sans matière rend une enquête vide, sans exception")
    void anEmptyPeriodIsValid() {
        given(List.of(), List.of());
        assertThat(service.survey(userId, FROM, TO).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("ISOLATION — les deux lectures portent le user_id, jamais un autre")
    void bothReadsCarryTheAccount() {
        given(List.of(turn(projectA, "1.00", 1000, 9000)), List.of());

        service.survey(userId, FROM, TO);

        verify(usageTurns).findByUserIdAndOccurredAtBetweenOrderByOccurredAtAsc(eq(userId), any(), eq(TO));
        verify(runnerAudit).findByUserIdAndCreatedAtBetweenOrderByCreatedAtAsc(eq(userId), any(), eq(TO));
    }
}
