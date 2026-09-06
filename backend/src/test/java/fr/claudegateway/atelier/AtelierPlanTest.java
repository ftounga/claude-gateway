package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.AtelierPlan.Status;

/**
 * Normalisation du plan de travail (F-39 / SF-39-13) : tout est corrigé, rien n'est refusé — un
 * outil d'organisation qui casse le travail qu'il organise serait pire que son absence (D2).
 */
class AtelierPlanTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AtelierPlan parse(String json) {
        try {
            return AtelierPlan.from(MAPPER.readTree(json));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void keepsTheStepsInOrderWithTheirStatus() {
        AtelierPlan plan = parse("""
                [{"title":"Lire la procédure","status":"done"},
                 {"title":"Générer le backend","status":"active"},
                 {"title":"Lancer les builds","status":"pending"}]""");

        assertThat(plan.steps()).extracting(AtelierPlan.Step::title)
                .containsExactly("Lire la procédure", "Générer le backend", "Lancer les builds");
        assertThat(plan.steps()).extracting(AtelierPlan.Step::status)
                .containsExactly(Status.DONE, Status.ACTIVE, Status.PENDING);
    }

    @Test
    void turnsAnUnknownStatusIntoPendingRatherThanRefusing() {
        AtelierPlan plan = parse("""
                [{"title":"Étape","status":"wip"}, {"title":"Autre"}]""");

        assertThat(plan.steps()).extracting(AtelierPlan.Step::status)
                .containsExactly(Status.PENDING, Status.PENDING);
    }

    @Test
    void keepsOnlyTheFirstActiveStep() {
        // Deux étapes en cours n'ont pas de sens : la première l'emporte.
        AtelierPlan plan = parse("""
                [{"title":"A","status":"active"}, {"title":"B","status":"active"}]""");

        assertThat(plan.steps()).extracting(AtelierPlan.Step::status)
                .containsExactly(Status.ACTIVE, Status.PENDING);
    }

    @Test
    void dropsStepsWithoutATitleAndTrimsLongOnes() {
        AtelierPlan plan = parse("""
                [{"title":"   "}, {"title":"  Titre élagué  "}, {"title":"%s"}]"""
                .formatted("x".repeat(300)));

        assertThat(plan.steps()).hasSize(2);
        assertThat(plan.steps().get(0).title()).isEqualTo("Titre élagué");
        assertThat(plan.steps().get(1).title()).hasSize(AtelierPlan.MAX_TITLE_CHARS);
    }

    @Test
    void capsThePlanAtTwentySteps() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 30; i++) {
            json.append(i > 0 ? "," : "").append("{\"title\":\"étape ").append(i).append("\"}");
        }
        AtelierPlan plan = parse(json.append("]").toString());

        assertThat(plan.steps()).hasSize(AtelierPlan.MAX_STEPS);
        // Le dépassement est DIT au modèle, pour qu'il puisse se corriger de lui-même.
        assertThat(plan.acknowledgement(30)).contains("20 premières");
    }

    @Test
    void treatsAnAbsentOrEmptyListAsAnErasedPlan() {
        assertThat(AtelierPlan.from(null).isEmpty()).isTrue();
        assertThat(parse("[]").isEmpty()).isTrue();
        assertThat(parse("[]").acknowledgement(0)).isEqualTo("Plan effacé.");
    }

    @Test
    void acknowledgesWhatWasKept() {
        assertThat(parse("""
                [{"title":"A"},{"title":"B"}]""").acknowledgement(2))
                .isEqualTo("Plan enregistré : 2 étape(s).");
    }
}
