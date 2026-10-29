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
    void planStepsComeFirstInTheChantierColumnAndDeepeningsGoRight() {
        ChantierAnchor.Anchored result = ChantierAnchor.apply(
                List.of(step("KMS", null), step("Étape 5", 5), step("SIEM", 0), step("Étape 4", 4)),
                journey(JourneyMode.GUIDE, JourneyPhase.EXECUTION, plan()));

        // F-209 : la prochaine étape débloquée d'abord (4), puis l'étape bloquée par la 4 ; à droite, les
        // approfondissements (jusqu'à 3).
        assertThat(result.steps()).extracting(NextStep::title).containsExactly("Étape 4", "Étape 5", "KMS", "SIEM");
        assertThat(result.steps()).extracting(NextStep::column).containsExactly(NextStep.Column.CHANTIER,
                NextStep.Column.CHANTIER, NextStep.Column.HORS, NextStep.Column.HORS);
        assertThat(result.steps()).extracting(NextStep::scope)
                .containsExactly(Scope.CHANTIER, Scope.CHANTIER, Scope.APPROFONDISSEMENT, Scope.APPROFONDISSEMENT);
        assertThat(result.steps().get(1).blockedBy()).containsExactly("étape 4");
        assertThat(result.steps().get(0).blockedBy()).isNull();
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

    // ------------------------------------------------------------------ F-206 / SF-206-03

    private static NextStep step(String title, Integer proposedStep, Integer proposedChantier) {
        return NextStep.proposed(title, "fait du tour", Label.CONTINUER, "Fais " + title + ".", proposedStep,
                proposedChantier);
    }

    /** Focus = chantier 2 (plan de 10) ; chantier 1 ouvert, plan de 2 étapes (1 faite). */
    private static SubjectJourney twoChantiers(String focusPlan) {
        SubjectJourney j = journey(JourneyMode.GUIDE, JourneyPhase.EXECUTION, focusPlan);
        j.setOtherOpenChantiers(List.of(fr.claudegateway.atelier.journey.SubjectJourneyChantier.builder()
                .number(1).status("OPEN").phase(JourneyPhase.EXECUTION)
                .planJson("[{\"title\":\"Remplacer le certificat\",\"risk\":\"EXTERNE\",\"status\":\"FAIT\"},"
                        + "{\"title\":\"Vérifier l'ingress\",\"risk\":\"LECTURE\"}]")
                .build()));
        return j;
    }

    @Test
    void aStepOfAnotherOpenChantierIsAnchoredToThatChantier() {
        ChantierAnchor.Anchored result = ChantierAnchor.apply(List.of(
                        step("KMS", null, null), step("Ingress", 2, 1), step("Focus", 4, null),
                        step("Cert", null, 1), step("Inconnu", 1, 7)),
                twoChantiers(plan()));

        // F-209 : à gauche le focus ; à droite, dans l'ordre du modèle, au plus 3 — dont UNE seule option
        // d'un autre chantier (« Cert » est écartée).
        assertThat(result.steps()).extracting(NextStep::title).containsExactly("Focus", "KMS", "Ingress", "Inconnu");
        assertThat(result.steps()).extracting(NextStep::column).containsExactly(NextStep.Column.CHANTIER,
                NextStep.Column.HORS, NextStep.Column.HORS, NextStep.Column.HORS);
        NextStep focus = result.steps().get(0);
        assertThat(focus.chantier()).isEqualTo(2);
        assertThat(focus.stepIndex()).isEqualTo(4);
        NextStep other = result.steps().get(2);
        assertThat(other.scope()).isEqualTo(Scope.CHANTIER);
        assertThat(other.chantier()).isEqualTo(1);
        assertThat(other.stepIndex()).isEqualTo(2);
        assertThat(other.stepTitle()).isEqualTo("Vérifier l'ingress");
        // « Inconnu » (chantier 7) et « KMS » sont hors plan : des approfondissements.
        assertThat(result.steps().get(1).scope()).isEqualTo(Scope.APPROFONDISSEMENT);
        assertThat(result.steps().get(3).scope()).isEqualTo(Scope.APPROFONDISSEMENT);
        assertThat(result.steps().get(3).chantier()).isNull();
        assertThat(result.chantier()).isEqualTo(new ChantierAnchor.Progress(2, 3, 10,
                List.of(new ChantierAnchor.Progress(1, 1, 2))));
    }

    @Test
    void aFocusWithoutPlanStillAnchorsTheOtherChantier() {
        ChantierAnchor.Anchored result = ChantierAnchor.apply(List.of(step("Ingress", 1, 1), step("KMS", 3, null)),
                twoChantiers(null));
        assertThat(result.steps()).extracting(NextStep::scope).containsExactly(Scope.CHANTIER, Scope.APPROFONDISSEMENT);
        assertThat(result.chantier()).isEqualTo(new ChantierAnchor.Progress(2, 0, 0,
                List.of(new ChantierAnchor.Progress(1, 1, 2))));
    }

    @Test
    void theProposedChantierIsReadFromTheBlockAndSerialized() throws Exception {
        String raw = "Ok.\n<<suites>>\n[" + String.join(",",
                "{\"titre\":\"A\",\"pourquoi\":\"p\",\"etiquette\":\"Continuer\",\"demande\":\"a\",\"etape\":1,\"chantier\":1}",
                "{\"titre\":\"B\",\"pourquoi\":\"p\",\"etiquette\":\"Continuer\",\"demande\":\"b\",\"chantier\":\"chantier 3\"}",
                "{\"titre\":\"C\",\"pourquoi\":\"p\",\"etiquette\":\"Continuer\",\"demande\":\"c\",\"chantier\":\"deux\"}")
                + "]\n<</suites>>";
        List<NextStep> steps = NextStepsBlock.extract(raw).steps();
        assertThat(steps).extracting(NextStep::proposedChantier).containsExactly(1, 3, null);
        assertThat(MAPPER.valueToTree(steps.get(0)).has("proposedChantier")).isFalse();

        NextStep anchored = ChantierAnchor.apply(steps, twoChantiers(plan())).steps().get(0);
        JsonNode json = MAPPER.valueToTree(anchored);
        assertThat(json.get("chantier").asInt()).isEqualTo(1);
        assertThat(json.get("stepTitle").asText()).isEqualTo("Remplacer le certificat");
        // Un chantier seul : pas de champ « others » (forme F-196 inchangée).
        ChantierAnchor.Anchored single = ChantierAnchor.apply(List.of(step("Étape 4", 4)),
                journey(JourneyMode.GUIDE, JourneyPhase.EXECUTION, plan()));
        assertThat(MAPPER.valueToTree(single.chantier()).has("others")).isFalse();
    }

    // ------------------------------------------------------------------ F-209 / SF-209-01

    private static SubjectJourney executing(String planJson) {
        return SubjectJourney.builder().mode(JourneyMode.GUIDE).phase(JourneyPhase.EXECUTION).planJson(planJson)
                .chantierNumber(4).chantierTitle("Migration").planVersion(2).validatedVersion(2).build();
    }

    /** 1 fait ; 2 lecture ; 3 externe dépend de 1 ; 4 dépend de 2 et 3 ; 5 externe dépend de 2 ; 6 dépend de 4 et 5 ; 7 attend. */
    private static final String DEPS = "["
            + "{\"title\":\"Lire\",\"risk\":\"LECTURE\",\"status\":\"FAIT\"},"
            + "{\"title\":\"Sauvegarder\",\"risk\":\"REVERSIBLE\"},"
            + "{\"title\":\"Prévenir l'équipe\",\"risk\":\"EXTERNE\",\"dependsOn\":[1]},"
            + "{\"title\":\"Migrer\",\"risk\":\"REVERSIBLE\",\"dependsOn\":[2,3]},"
            + "{\"title\":\"Couper l'ancien\",\"risk\":\"EXTERNE\",\"dependsOn\":[2]},"
            + "{\"title\":\"Basculer\",\"risk\":\"EXTERNE\",\"dependsOn\":[4,5]},"
            + "{\"title\":\"Annoncer\",\"risk\":\"NOTES\",\"waitsOn\":\"feu-vert\",\"dependsOn\":[1]}]";

    @Test
    void columnsHaveSeparateCapsAndTheChantierColumnIsOrdered() {
        List<NextStep> input = List.of(
                step("Vérifier 1", 1), step("Mail client", null), step("Annoncer", 7), step("Sauvegarder", 2),
                step("Slack", null), step("Prévenir", 3), step("Doc", null), step("Basculer", 6));
        ChantierAnchor.Anchored result = ChantierAnchor.apply(input, executing(DEPS), java.util.Map.of("feu-vert", "DEMANDE"));

        assertThat(result.steps()).extracting(NextStep::title)
                // Chantier (4 au plus) : débloquées par numéro (2, 3), puis bloquées (7, 6) ; « Vérifier 1 »
                // (faite → vérification) passe après, donc hors plafond. Hors (3 au plus) : ordre du modèle.
                .containsExactly("Sauvegarder", "Prévenir", "Annoncer", "Basculer", "Mail client", "Slack", "Doc");
        assertThat(result.steps().get(2).blockedBy()).containsExactly("attente feu-vert");
        assertThat(result.steps().get(3).blockedBy()).containsExactly("étape 4", "étape 5");
        assertThat(result.chantier()).isEqualTo(new ChantierAnchor.Progress(4, 1, 7, null, "Migration"));
    }

    @Test
    void verificationComesAfterTheNextStepAndTheBlocked() {
        ChantierAnchor.Anchored result = ChantierAnchor.apply(
                List.of(step("Vérifier 1", 1), step("Annoncer", 7), step("Sauvegarder", 2)),
                executing(DEPS), java.util.Map.of("feu-vert", "A_FAIRE"));
        assertThat(result.steps()).extracting(NextStep::title).containsExactly("Sauvegarder", "Annoncer", "Vérifier 1");
    }

    @Test
    void prerequisitesAreTheTransitiveUndoneDependenciesByLevelAndNeverTwoExternalInParallel() {
        // L'étape 6 dépend de 4 et 5 ; 4 de 2 et 3 ; 5 de 2. 1 est faite.
        List<NextStep.Prerequisite> pre = ChantierAnchor.apply(List.of(step("Basculer", 6)), executing(DEPS))
                .steps().get(0).prerequisites();
        assertThat(pre).extracting(NextStep.Prerequisite::step).containsExactly(2, 3, 4, 5);
        // Niveau 1 : 2 et 3 en parallèle (une seule EXTERNE). Niveau 2 : 4 et 5 (une EXTERNE). Rien à rétrograder.
        assertThat(pre).extracting(NextStep.Prerequisite::level).containsExactly(1, 1, 2, 2);
        assertThat(pre.get(1).risk()).isEqualTo("EXTERNE");

        // Deux EXTERNE au même niveau : la seconde passe au niveau suivant.
        String twoExternal = "[{\"title\":\"A\",\"risk\":\"EXTERNE\"},{\"title\":\"B\",\"risk\":\"EXTERNE\"},"
                + "{\"title\":\"C\",\"risk\":\"LECTURE\"},"
                + "{\"title\":\"D\",\"risk\":\"REVERSIBLE\",\"dependsOn\":[1,2,3]}]";
        List<NextStep.Prerequisite> ext = ChantierAnchor.apply(List.of(step("D", 4)), executing(twoExternal))
                .steps().get(0).prerequisites();
        assertThat(ext).extracting(NextStep.Prerequisite::step).containsExactly(1, 3, 2);
        assertThat(ext).extracting(NextStep.Prerequisite::level).containsExactly(1, 1, 2);

        // Sans dépendance déclarée : aucun prérequis (jamais deviné).
        assertThat(ChantierAnchor.apply(List.of(step("Étape 5", 5)), executing(plan())).steps().get(0)
                .prerequisites()).isNull();
    }

    @Test
    void theChantierColumnIsNeverEmptyWhileAStepRemains() {
        ChantierAnchor.Anchored result = ChantierAnchor.apply(List.of(step("Mail client", null)),
                executing(DEPS), java.util.Map.of());
        assertThat(result.steps()).extracting(NextStep::title).containsExactly("Continuer : étape 2", "Mail client");
        NextStep derived = result.steps().get(0);
        assertThat(derived.column()).isEqualTo(NextStep.Column.CHANTIER);
        assertThat(derived.stepIndex()).isEqualTo(2);
        assertThat(derived.request()).isEqualTo("Chantier 4 — fais l’étape 2 : Sauvegarder");
        // Plan en attente de validation : pas de repli.
        SubjectJourney planning = executing(DEPS);
        planning.setPhase(JourneyPhase.PLAN);
        assertThat(ChantierAnchor.apply(List.of(step("Mail client", null)), planning).steps())
                .extracting(NextStep::title).containsExactly("Mail client");
    }

    @Test
    void withoutAnOpenChantierASingleColumnOfFourAtMost() throws Exception {
        List<NextStep> five = List.of(step("A", null), step("B", null), step("C", null), step("D", null),
                step("E", null));
        ChantierAnchor.Anchored libre = ChantierAnchor.apply(five, null);
        assertThat(libre.steps()).extracting(NextStep::title).containsExactly("A", "B", "C", "D");
        assertThat(libre.steps()).allSatisfy(s -> assertThat(s.column()).isNull());
        assertThat(MAPPER.valueToTree(libre.steps().get(0)).has("column")).isFalse();
        JsonNode anchored = MAPPER.valueToTree(ChantierAnchor.apply(List.of(step("Basculer", 6)), executing(DEPS))
                .steps().get(0));
        assertThat(anchored.get("column").asText()).isEqualTo("CHANTIER");
        assertThat(anchored.get("prerequisites").get(0).get("level").asInt()).isEqualTo(1);
        assertThat(anchored.get("blockedBy").get(0).asText()).isEqualTo("étape 4");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("F-221 / SF-221-05 : un volet du split ne garde que la colonne de son chantier")
    void chantierOnlyDropsTheHorsColumn() {
        NextStep chantier = new NextStep("a", "w", null, "d", null, null, null, 8, NextStep.Column.CHANTIER,
                null, null, null, null);
        NextStep hors = new NextStep("b", "w", null, "d", null, null, null, null, NextStep.Column.HORS,
                null, null, null, null);
        NextStep plain = new NextStep("c", "w", null, "d", null, null, null, null, null, null, null, null, null);
        ChantierAnchor.Anchored anchored = new ChantierAnchor.Anchored(java.util.List.of(chantier, hors, plain), null);

        org.assertj.core.api.Assertions.assertThat(ChantierAnchor.chantierOnly(anchored).steps())
                .containsExactly(chantier, plain);
        org.assertj.core.api.Assertions.assertThat(ChantierAnchor.chantierOnly(null)).isNull();
    }
}
