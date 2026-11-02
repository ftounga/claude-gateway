package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Le banc de conversation (F-239 / SF-239-07) : jeu, grille, budget en €, porte. */
class JarvisConversationBenchTest {

    private static final JarvisConversationBench.Set SET = JarvisConversationBench.load();

    private static JarvisConversationBench.TurnVerdict good(String id, int turn, String route, String expect) {
        return new JarvisConversationBench.TurnVerdict(id, turn, "X", route, expect, true, 4, true, false, false, true,
                false, false, false, "Bonjour, Monsieur.", "ok");
    }

    private static JarvisConversationBench.Scenario scenario(String id, String... expects) {
        return new JarvisConversationBench.Scenario(id, "X", null, java.util.Arrays.stream(expects)
                .map(e -> new JarvisConversationBench.Turn("x", e)).toList());
    }

    @Test
    void theSetHasAboutThirtyScenariosAllCategoriesAndTheRealTurnsOfThe2ndOfNovember() {
        assertThat(SET.scenarios()).hasSizeGreaterThanOrEqualTo(30);
        assertThat(SET.scenarios()).extracting(JarvisConversationBench.Scenario::category).contains("SALUTATION",
                "PETITE_CONVERSATION", "COMMENCONS", "POINT", "METIER", "MAL_ENTENDU", "INTERRUPTION", "SILENCE",
                "REMERCIEMENT", "AU_REVOIR", "REEL_02_11");
        assertThat(SET.scenarios()).filteredOn(s -> s.category().equals("INTERRUPTION"))
                .allSatisfy(s -> assertThat(s.heardBeforeCut()).isNotBlank());
        assertThat(SET.opusTurnEur()).isPositive();
    }

    @Test
    void aWellHandledScenarioPassesWithinItsBudget() {
        JarvisConversationBench.ScenarioVerdict v = JarvisConversationBench.scenario(scenario("S", "repondre",
                "deleguer"), List.of(good("S", 1, "repondre", "repondre"), good("S", 2, "deleguer", "deleguer")), SET,
                List.of(new BigDecimal("0.01"), new BigDecimal("0.01")));

        assertThat(v.passed()).isTrue();
        assertThat(v.delegations()).isEqualTo(1);
        assertThat(v.costEur()).isLessThanOrEqualTo(v.budgetEur());
    }

    @Test
    void aGreetingDelegatedToOpusBlowsTheBudgetAndFails() {
        JarvisConversationBench.TurnVerdict delegated = new JarvisConversationBench.TurnVerdict("S", 1, "X",
                "deleguer", "repondre", true, 2, true, true, true, true, false, false, false, "Je vérifie, Monsieur.",
                "délégation inutile");
        JarvisConversationBench.ScenarioVerdict v = JarvisConversationBench.scenario(scenario("S", "repondre"),
                List.of(delegated), SET, List.of(new BigDecimal("0.01")));

        assertThat(v.passed()).isFalse();
        assertThat(v.reasons()).anyMatch(r -> r.contains("budget dépassé")).anyMatch(r -> r.contains("délégation"))
                .anyMatch(r -> r.contains("formule robotique")).anyMatch(r -> r.contains("route deleguer"));
    }

    @Test
    void moreThanOneReturnQuestionInAScenarioFails() {
        JarvisConversationBench.TurnVerdict asks = new JarvisConversationBench.TurnVerdict("S", 1, "X", "repondre",
                "repondre", true, 4, true, false, false, true, true, false, false, "Et vous ?", "ok");
        JarvisConversationBench.ScenarioVerdict v = JarvisConversationBench.scenario(scenario("S", "repondre",
                "repondre"), List.of(asks, asks), SET, List.of(BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(v.passed()).isFalse();
        assertThat(v.reasons()).anyMatch(r -> r.contains("question en retour"));
    }

    @Test
    void theGateNeedsNinetyPercentAndZeroUselessDelegationRoboticUngrounded() {
        List<JarvisConversationBench.TurnVerdict> turns = List.of(good("A", 1, "repondre", "repondre"));
        JarvisConversationBench.ScenarioVerdict ok = new JarvisConversationBench.ScenarioVerdict("A", "X", true,
                new BigDecimal("0.01"), new BigDecimal("0.02"), 0, List.of());
        JarvisConversationBench.Result pass = JarvisConversationBench.summarize(turns, List.of(ok));
        assertThat(pass.gatePassed()).isTrue();
        assertThat(pass.passRate()).isEqualTo(100.0);
        assertThat(pass.costPerExchangeEur()).isEqualByComparingTo("0.01");
        assertThat(pass.delegationRate()).isZero();

        JarvisConversationBench.ScenarioVerdict ko = new JarvisConversationBench.ScenarioVerdict("B", "X", false,
                new BigDecimal("1"), new BigDecimal("0.02"), 1, List.of("B budget dépassé"));
        JarvisConversationBench.Result fail = JarvisConversationBench.summarize(turns, List.of(ok, ko));
        assertThat(fail.gatePassed()).isFalse();
        assertThat(fail.failures()).anyMatch(f -> f.contains("< 90 %")).anyMatch(f -> f.contains("budget"));
    }
}
