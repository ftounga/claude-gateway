package fr.claudegateway.atelier.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.journey.ChantierPreflight.Code;

/** F-219 / SF-219-01 — le contrôle avant départ, calcul pur. */
class ChantierPreflightTest {

    private static String step(String title, String status, String extra) {
        return "{\"title\":\"" + title + "\",\"risk\":\"REVERSIBLE\",\"status\":\"" + status + "\"" + extra + "}";
    }

    private static String plan(String... steps) {
        return "[" + String.join(",", steps) + "]";
    }

    private static ChantierPreflight run(JourneyPhase phase, String planJson, Integer validated,
                                         Map<String, String> waits, boolean question) {
        return ChantierPreflight.evaluate(new ChantierPreflight.Input(1, true, phase, planJson, 3, validated, waits,
                question));
    }

    private static ChantierPreflight executing(String planJson, Map<String, String> waits) {
        return run(JourneyPhase.EXECUTION, planJson, 3, waits, false);
    }

    @Test
    @DisplayName("plan validé en exécution, rien ne bloque : prêt à partir")
    void ready() {
        ChantierPreflight p = executing(plan(step("A", "FAIT", ""), step("B", "A_FAIRE", "")), Map.of());
        assertThat(p.ready()).isTrue();
        assertThat(p.reasons()).isEmpty();
        assertThat(p.number()).isEqualTo(1);
    }

    @Test
    @DisplayName("en vérification avec des étapes faites non vérifiées : prêt")
    void readyInVerification() {
        ChantierPreflight p = run(JourneyPhase.VERIFICATION, plan(step("A", "FAIT", "")), 3, Map.of(), false);
        assertThat(p.ready()).isTrue();
    }

    @Test
    @DisplayName("amendement en attente : PLAN_NOT_VALIDATED")
    void amendment() {
        ChantierPreflight p = run(JourneyPhase.EXECUTION, plan(step("A", "A_FAIRE", "")), 2, Map.of(), false);
        assertThat(p.ready()).isFalse();
        assertThat(p.reasons()).extracting(ChantierPreflight.Reason::code).containsExactly(Code.PLAN_NOT_VALIDATED);
        assertThat(p.reasons().get(0).label()).contains("amendé");
    }

    @Test
    @DisplayName("investigation sans plan : PLAN_NOT_VALIDATED + NOT_EXECUTING")
    void investigation() {
        ChantierPreflight p = run(JourneyPhase.INVESTIGATION, null, null, Map.of(), false);
        assertThat(p.reasons()).extracting(ChantierPreflight.Reason::code)
                .containsExactly(Code.PLAN_NOT_VALIDATED, Code.NOT_EXECUTING);
    }

    @Test
    @DisplayName("sujet libre : NOT_GUIDED")
    void notGuided() {
        ChantierPreflight p = ChantierPreflight.evaluate(new ChantierPreflight.Input(1, false, JourneyPhase.EXECUTION,
                plan(step("A", "A_FAIRE", "")), 3, 3, Map.of(), false));
        assertThat(p.reasons()).extracting(ChantierPreflight.Reason::code).containsExactly(Code.NOT_GUIDED);
    }

    @Test
    @DisplayName("étape BLOQUE : STEP_BLOCKED avec sa raison ; une étape qui en dépend : DEPENDENCY_BLOCKED")
    void blocked() {
        ChantierPreflight p = executing(plan(step("A", "BLOQUE", ",\"evidence\":\"accès VPN refusé\""),
                step("B", "A_FAIRE", ",\"dependsOn\":[1]")), Map.of());
        assertThat(p.ready()).isFalse();
        assertThat(p.reasons()).extracting(ChantierPreflight.Reason::code)
                .containsExactly(Code.STEP_BLOCKED, Code.DEPENDENCY_BLOCKED);
        assertThat(p.reasons().get(0).label()).isEqualTo("Étape 1 bloquée : accès VPN refusé");
        assertThat(p.reasons().get(0).step()).isEqualTo(1);
        assertThat(p.reasons().get(1).step()).isEqualTo(2);
    }

    @Test
    @DisplayName("étape ECHEC : STEP_FAILED")
    void failed() {
        ChantierPreflight p = executing(plan(step("A", "ECHEC", ",\"evidence\":\"500\"")), Map.of());
        assertThat(p.reasons()).extracting(ChantierPreflight.Reason::code).containsExactly(Code.STEP_FAILED);
    }

    @Test
    @DisplayName("attente ouverte : WAIT_OPEN ; sans attente : MISSING_INFO ; attente faite : rien")
    void waits() {
        String json = plan(step("A", "A_FAIRE", ",\"waitsOn\":\"cert-gino\""),
                step("B", "A_FAIRE", ",\"waitsOn\":\"mdp-db\""),
                step("C", "A_FAIRE", ",\"waitsOn\":\"go-rssi\""));
        ChantierPreflight p = executing(json, Map.of("cert-gino", "DEMANDE", "go-rssi", "FAIT"));
        assertThat(p.reasons()).extracting(ChantierPreflight.Reason::code)
                .containsExactly(Code.WAIT_OPEN, Code.MISSING_INFO);
        assertThat(p.reasons().get(0).label()).contains("cert-gino").contains("demandé");
        assertThat(p.reasons().get(1).label()).contains("mdp-db");
    }

    @Test
    @DisplayName("une attente sur une étape déjà faite ne bloque pas")
    void waitOnDoneStep() {
        ChantierPreflight p = executing(plan(step("A", "FAIT", ",\"waitsOn\":\"k\""), step("B", "A_FAIRE", "")),
                Map.of());
        assertThat(p.ready()).isTrue();
    }

    @Test
    @DisplayName("question en suspens : QUESTION_PENDING")
    void question() {
        ChantierPreflight p = run(JourneyPhase.EXECUTION, plan(step("A", "A_FAIRE", "")), 3, Map.of(), true);
        assertThat(p.reasons()).extracting(ChantierPreflight.Reason::code).containsExactly(Code.QUESTION_PENDING);
    }

    @Test
    @DisplayName("tout vérifié : NOTHING_LEFT")
    void nothingLeft() {
        ChantierPreflight p = run(JourneyPhase.VERIFICATION, plan(step("A", "VERIFIE", ",\"evidence\":\"ok\"")), 3,
                Map.of(), false);
        assertThat(p.reasons()).extracting(ChantierPreflight.Reason::code).containsExactly(Code.NOTHING_LEFT);
    }

    @Test
    @DisplayName("BLOQUE se relit et se réécrit à l'identique")
    void roundTrip() {
        String json = plan(step("A", "BLOQUE", ",\"evidence\":\"x\""));
        JourneyPlan plan = JourneyPlan.fromJson(json);
        assertThat(plan.steps().get(0).status()).isEqualTo(JourneyPlan.StepStatus.BLOQUE);
        assertThat(JourneyPlan.fromJson(plan.toJson()).steps().get(0).status())
                .isEqualTo(JourneyPlan.StepStatus.BLOQUE);
    }
}
