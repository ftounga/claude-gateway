package fr.claudegateway.atelier.nextsteps;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.journey.JourneyMode;
import fr.claudegateway.atelier.journey.JourneyPhase;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.nextsteps.NextStep.Label;
import fr.claudegateway.atelier.nextsteps.NextStep.Scope;

/** F-196 / SF-196-01 : les suites rattachées au plan réel du chantier. */
class ChantierAnchorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Plan de 10 étapes : 2 vérifiées, 1 faite, 7 à faire (le constat WAF). */
    private static String plan() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 1; i <= 10; i++) {
            String status = i <= 2 ? "VERIFIE" : i == 3 ? "FAIT" : "A_FAIRE";
            String title = i == 2 ? "Appliquer la policy NAP V12 sur l'ingress de production, après revue des "
                    + "règles de signature" : "Étape réelle " + i;
            json.append(i > 1 ? "," : "").append("{\"title\":\"").append(title)
                    .append("\",\"risk\":\"REVERSIBLE\",\"status\":\"").append(status).append("\"}");
        }
        return json.append("]").toString();
    }

    private static SubjectJourney journey(JourneyMode mode, JourneyPhase phase, String planJson) {
        return SubjectJourney.builder().mode(mode).phase(phase).planJson(planJson).chantierNumber(2).build();
    }

    private static NextStep step(String title, Integer proposed) {
        return NextStep.proposed(title, "fait du tour", Label.CONTINUER, "Fais " + title + ".", proposed);
    }

    @Test
    void aValidNumberIsAnchoredToTheRealPlanTitle() {
        ChantierAnchor.Anchored result = ChantierAnchor.apply(
                List.of(step("NAP", 2)), journey(JourneyMode.GUIDE, JourneyPhase.EXECUTION, plan()));

        NextStep anchored = result.steps().get(0);
        assertThat(anchored.scope()).isEqualTo(Scope.CHANTIER);
        assertThat(anchored.stepIndex()).isEqualTo(2);
        assertThat(anchored.stepTitle()).hasSizeLessThanOrEqualTo(ChantierAnchor.MAX_STEP_TITLE_CHARS)
                .startsWith("Appliquer la policy NAP V12").endsWith("…");
        assertThat(anchored.proposedStep()).isNull();
    }

    @Test
    void aMissingOrOutOfPlanNumberIsADeepening() {
        ChantierAnchor.Anchored result = ChantierAnchor.apply(
                List.of(step("KMS", null)), journey(JourneyMode.GUIDE, JourneyPhase.EXECUTION, plan()));
        assertThat(result.steps()).extracting(NextStep::scope).containsExactly(Scope.APPROFONDISSEMENT);
        assertThat(result.steps().get(0).stepIndex()).isNull();

        ChantierAnchor.Anchored outOfPlan = ChantierAnchor.apply(
                List.of(step("SIEM", 11)), journey(JourneyMode.GUIDE, JourneyPhase.EXECUTION, plan()));
        assertThat(outOfPlan.steps()).extracting(NextStep::scope).containsExactly(Scope.APPROFONDISSEMENT);
    }

    @Test
    void planStepsComeFirstAndOnlyOneDeepeningIsKeptLast() {
        ChantierAnchor.Anchored result = ChantierAnchor.apply(
                List.of(step("KMS", null), step("Étape 4", 4), step("SIEM", 0), step("Étape 5", 5)),
                journey(JourneyMode.GUIDE, JourneyPhase.EXECUTION, plan()));

        assertThat(result.steps()).extracting(NextStep::title).containsExactly("Étape 4", "Étape 5", "KMS");
        assertThat(result.steps()).extracting(NextStep::scope)
                .containsExactly(Scope.CHANTIER, Scope.CHANTIER, Scope.APPROFONDISSEMENT);
    }

    @Test
    void progressCountsDoneAndVerifiedSteps() {
        ChantierAnchor.Anchored result = ChantierAnchor.apply(
                List.of(step("Étape 4", 4)), journey(JourneyMode.GUIDE, JourneyPhase.EXECUTION, plan()));

        assertThat(result.chantier()).isEqualTo(new ChantierAnchor.Progress(2, 3, 10));
    }

    @Test
    void libreClosedOrPlanlessJourneysLeaveStepsUnanchored() {
        List<NextStep> steps = List.of(step("A", 2), step("B", null), step("C", null));
        for (SubjectJourney j : new SubjectJourney[] {
                null,
                journey(JourneyMode.LIBRE, JourneyPhase.EXECUTION, plan()),
                journey(JourneyMode.LIBRE, JourneyPhase.CLOS, plan()),
                journey(JourneyMode.GUIDE, JourneyPhase.CLOS, plan()),
                journey(JourneyMode.GUIDE, JourneyPhase.INVESTIGATION, null),
                journey(JourneyMode.GUIDE, JourneyPhase.EXECUTION, "{illisible")}) {
            ChantierAnchor.Anchored result = ChantierAnchor.apply(steps, j);
            assertThat(result.chantier()).isNull();
            assertThat(result.steps()).extracting(NextStep::title).containsExactly("A", "B", "C");
            assertThat(result.steps()).allSatisfy(s -> {
                assertThat(s.scope()).isNull();
                assertThat(s.stepIndex()).isNull();
                assertThat(s.proposedStep()).isNull();
            });
        }
    }

    @Test
    void serializationOmitsAnchorFieldsOutsideAChantierAndNeverExposesTheProposedNumber() throws Exception {
        NextStep libre = ChantierAnchor.apply(List.of(step("A", 3)), null).steps().get(0);
        JsonNode libreJson = MAPPER.valueToTree(libre);
        assertThat(libreJson.has("scope")).isFalse();
        assertThat(libreJson.has("stepIndex")).isFalse();
        assertThat(libreJson.has("stepTitle")).isFalse();
        assertThat(libreJson.has("proposedStep")).isFalse();

        NextStep raw = step("A", 3);
        assertThat(MAPPER.valueToTree(raw).has("proposedStep")).isFalse();

        NextStep anchored = ChantierAnchor.apply(List.of(step("A", 3)),
                journey(JourneyMode.GUIDE, JourneyPhase.EXECUTION, plan())).steps().get(0);
        JsonNode json = MAPPER.valueToTree(anchored);
        assertThat(json.get("scope").asText()).isEqualTo("CHANTIER");
        assertThat(json.get("stepIndex").asInt()).isEqualTo(3);
        assertThat(json.get("stepTitle").asText()).isEqualTo("Étape réelle 3");
    }

    @Test
    void attachToStoresTheProgressNextToTheSteps() throws Exception {
        ChantierAnchor.Anchored result = ChantierAnchor.apply(List.of(step("Étape 4", 4)),
                journey(JourneyMode.GUIDE, JourneyPhase.EXECUTION, plan()));

        JsonNode report = MAPPER.readTree(NextStepsBlock.attachTo("{\"a\":1}", result.steps(), result.chantier()));

        assertThat(report.get("a").asInt()).isEqualTo(1);
        assertThat(report.get("nextSteps").get(0).get("stepIndex").asInt()).isEqualTo(4);
        assertThat(report.get("nextStepsChantier").get("done").asInt()).isEqualTo(3);
        assertThat(report.get("nextStepsChantier").get("total").asInt()).isEqualTo(10);
        assertThat(report.get("nextStepsChantier").get("number").asInt()).isEqualTo(2);
        assertThat(MAPPER.readTree(NextStepsBlock.attachTo("{}", result.steps(), null)).has("nextStepsChantier"))
                .isFalse();
    }

    @Test
    void theProposedStepIsReadFromTheBlock() {
        String raw = "Ok.\n<<suites>>\n[" + String.join(",",
                "{\"titre\":\"A\",\"pourquoi\":\"p\",\"etiquette\":\"Continuer\",\"demande\":\"a\",\"etape\":4}",
                "{\"titre\":\"B\",\"pourquoi\":\"p\",\"etiquette\":\"Continuer\",\"demande\":\"b\",\"étape\":\"étape 7\"}",
                "{\"titre\":\"C\",\"pourquoi\":\"p\",\"etiquette\":\"Continuer\",\"demande\":\"c\",\"etape\":null}",
                "{\"titre\":\"D\",\"pourquoi\":\"p\",\"etiquette\":\"Continuer\",\"demande\":\"d\",\"step\":-2}")
                + "]\n<</suites>>";

        List<NextStep> steps = NextStepsBlock.extract(raw).steps();

        assertThat(steps).extracting(NextStep::proposedStep).containsExactly(4, 7, null, null);
    }
}
