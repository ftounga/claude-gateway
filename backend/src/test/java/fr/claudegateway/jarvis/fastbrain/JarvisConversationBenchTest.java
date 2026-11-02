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
        assertThat(fail.failures()).anyMatch(f -> f.contains("< 90 %"));
        // F-239 / SF-239-13 — le détail d'un scénario échoué est dans le rapport, pas dans les échecs de la porte.
        assertThat(fail.failures()).noneMatch(f -> f.contains("budget"));
        assertThat(fail.scenarios()).anyMatch(s -> s.reasons().contains("B budget dépassé"));

        // ≥ 90 % et aucun zéro enfreint : la porte passe, même avec un scénario échoué (sens faux).
        List<JarvisConversationBench.ScenarioVerdict> mostly = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            mostly.add(new JarvisConversationBench.ScenarioVerdict("S" + i, "X", i > 0, new BigDecimal("0.01"),
                    new BigDecimal("0.02"), 0, i > 0 ? List.of() : List.of("S0#1 sens faux")));
        }
        assertThat(JarvisConversationBench.summarize(turns, mostly).gatePassed()).isTrue();
    }

    @Test
    void f240EachTurnCarriesItsWordsAndTheReportGivesTheFastBrainP90() {
        JarvisConversationBench.TurnVerdict hello = good("S", 1, "repondre", "repondre");
        assertThat(hello.words()).isEqualTo(2);
        JarvisConversationBench.TurnVerdict longer = new JarvisConversationBench.TurnVerdict("S", 2, "X", "repondre",
                "any", true, 4, true, false, false, true, false, false, false,
                "Le chantier 3 en est aux tests d'intégration de la migration Keycloak.", "ok");
        JarvisConversationBench.TurnVerdict delegated = new JarvisConversationBench.TurnVerdict("S", 3, "X",
                "deleguer", "deleguer", true, 4, true, false, false, true, false, false, false,
                "Je regarde les tickets bloqués d'Edenred, Monsieur, et je reviens vers vous.", "ok");
        // Seules les réponses du cerveau rapide comptent (une annonce de délégation est à part).
        Integer[] words = JarvisConversationBench.fastWords(List.of(hello, longer, delegated));
        assertThat(words[0]).isEqualTo(2);
        assertThat(words[1]).isEqualTo(12);
        assertThat(JarvisConversationBench.fastWords(List.of(delegated))[1]).isNull();
        JarvisFastBrainBenchService.ConversationReport report = new JarvisFastBrainBenchService.ConversationReport(1.0,
                BigDecimal.ZERO, 0.0, List.of(), List.of(hello, longer));
        assertThat(report.fastWordsP90()).isEqualTo(12);
    }
}
