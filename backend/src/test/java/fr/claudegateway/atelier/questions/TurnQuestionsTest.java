package fr.claudegateway.atelier.questions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** F-210 / SF-210-01 — l'état des questions d'un tour. */
class TurnQuestionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private static TurnQuestions splitTwo() {
        TurnQuestions questions = new TurnQuestions();
        TurnQuestions.Outcome outcome = questions.apply(QuestionToolCatalog.SPLIT, json(
                "{\"questions\":[{\"id\":1,\"libelle\":\"Pourquoi le build échoue\"},"
                        + "{\"id\":2,\"libelle\":\"Mettre à jour le README\"}]}"));
        assertThat(outcome.error()).isFalse();
        return questions;
    }

    @Test
    void aSplitStartsTheFirstQuestionAndQueuesTheOthers() {
        TurnQuestions questions = splitTwo();

        assertThat(questions.isSplit()).isTrue();
        assertThat(questions.current()).isEqualTo(1);
        assertThat(questions.snapshot().questions()).extracting(TurnQuestions.QuestionView::state)
                .containsExactly("EN_COURS", "EN_ATTENTE");
        assertThat(questions.snapshot().fusion()).isNull();
    }

    @Test
    void aSingleQuestionWithoutFusionIsRefused() {
        TurnQuestions questions = new TurnQuestions();
        TurnQuestions.Outcome outcome = questions.apply(QuestionToolCatalog.SPLIT,
                json("{\"questions\":[{\"id\":1,\"libelle\":\"Une seule\"}]}"));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("deux demandes distinctes");
        assertThat(questions.isSplit()).isFalse();
        assertThat(questions.snapshot()).isNull();
    }

    @Test
    void aFusionKeepsItsReason() {
        TurnQuestions questions = new TurnQuestions();
        TurnQuestions.Outcome outcome = questions.apply(QuestionToolCatalog.SPLIT, json(
                "{\"questions\":[{\"id\":1,\"libelle\":\"A\"},{\"id\":2,\"libelle\":\"A reformulée\"}],"
                        + "\"fusion\":{\"raison\":\"la seconde remplace la première\"}}"));

        assertThat(outcome.error()).isFalse();
        assertThat(questions.snapshot().fusion().raison()).isEqualTo("la seconde remplace la première");
    }

    @Test
    void anInterjectionAddsQuestionsWithIncreasingIds() {
        TurnQuestions questions = splitTwo();

        assertThat(questions.apply(QuestionToolCatalog.SPLIT,
                json("{\"questions\":[{\"id\":2,\"libelle\":\"doublon\"}]}")).error()).isTrue();
        assertThat(questions.apply(QuestionToolCatalog.SPLIT,
                json("{\"questions\":[{\"id\":3,\"libelle\":\"Nouvelle demande\",\"depend_de\":1}]}"))
                .error()).isFalse();

        assertThat(questions.snapshot().questions()).extracting(TurnQuestions.QuestionView::id)
                .containsExactly(1, 2, 3);
        assertThat(questions.snapshot().questions().get(2).dependsOn()).isEqualTo(1);
        assertThat(questions.current()).isEqualTo(1);
    }

    @Test
    void anUnknownDependencyIsIgnoredAndSaid() {
        TurnQuestions questions = new TurnQuestions();
        TurnQuestions.Outcome outcome = questions.apply(QuestionToolCatalog.SPLIT, json(
                "{\"questions\":[{\"id\":1,\"libelle\":\"A\",\"depend_de\":9},{\"id\":2,\"libelle\":\"B\"}]}"));

        assertThat(outcome.content()).contains("ignorée");
        assertThat(questions.snapshot().questions().get(0).dependsOn()).isNull();
    }

    @Test
    void aQuestionThatWaitsOnAnotherIsNotStartedFirst() {
        TurnQuestions questions = new TurnQuestions();
        questions.apply(QuestionToolCatalog.SPLIT, json(
                "{\"questions\":[{\"id\":1,\"libelle\":\"A\"},{\"id\":2,\"libelle\":\"B\",\"depend_de\":1}]}"));
        assertThat(questions.current()).isEqualTo(1);
    }

    @Test
    void longLabelsAreTruncatedAndInvalidIdsRefused() {
        TurnQuestions questions = new TurnQuestions();
        String longLabel = "x".repeat(200);
        questions.apply(QuestionToolCatalog.SPLIT, json("{\"questions\":[{\"id\":1,\"libelle\":\"" + longLabel
                + "\"},{\"id\":2,\"libelle\":\"B\"}]}"));
        assertThat(questions.snapshot().questions().get(0).libelle()).hasSize(TurnQuestions.MAX_LABEL)
                .endsWith("…");

        TurnQuestions other = new TurnQuestions();
        assertThat(other.apply(QuestionToolCatalog.SPLIT,
                json("{\"questions\":[{\"id\":0,\"libelle\":\"A\"},{\"id\":2,\"libelle\":\"B\"}]}")).error())
                .isTrue();
        assertThat(other.apply(QuestionToolCatalog.SPLIT,
                json("{\"questions\":[{\"id\":1},{\"id\":2,\"libelle\":\"B\"}]}")).error()).isTrue();
        assertThat(other.isSplit()).isFalse();
    }

    @Test
    void tooManyQuestionsAreRefused() {
        StringBuilder list = new StringBuilder("{\"questions\":[");
        for (int i = 1; i <= TurnQuestions.MAX_QUESTIONS + 1; i++) {
            list.append(i > 1 ? "," : "").append("{\"id\":").append(i).append(",\"libelle\":\"q\"}");
        }
        TurnQuestions questions = new TurnQuestions();
        assertThat(questions.apply(QuestionToolCatalog.SPLIT, json(list + "]}")).error()).isTrue();
    }

    @Test
    void trackingMovesTheCurrentQuestionAndActionsFollowIt() {
        TurnQuestions questions = splitTwo();

        assertThat(questions.attach("call-a")).isEqualTo(1);
        questions.apply(QuestionToolCatalog.TRACK, json("{\"id\":2,\"etat\":\"en_cours\"}"));
        assertThat(questions.attach("call-b")).isEqualTo(2);
        questions.apply(QuestionToolCatalog.TRACK, json("{\"id\":2,\"etat\":\"TERMINEE\"}"));
        // Q2 terminée : la main revient à Q1, toujours en cours.
        assertThat(questions.attach("call-c")).isEqualTo(1);

        assertThat(questions.snapshot().questions().get(0).toolUseIds()).containsExactly("call-a", "call-c");
        assertThat(questions.snapshot().questions().get(1).toolUseIds()).containsExactly("call-b");
    }

    @Test
    void trackingErrors() {
        TurnQuestions none = new TurnQuestions();
        assertThat(none.apply(QuestionToolCatalog.TRACK, json("{\"id\":1,\"etat\":\"EN_COURS\"}")).error()).isTrue();
        TurnQuestions questions = splitTwo();
        assertThat(questions.apply(QuestionToolCatalog.TRACK, json("{\"id\":7,\"etat\":\"EN_COURS\"}")).error())
                .isTrue();
        assertThat(questions.apply(QuestionToolCatalog.TRACK, json("{\"id\":1,\"etat\":\"FINI\"}")).error())
                .isTrue();
    }

    @Test
    void noActionIsAttachedWithoutACurrentQuestion() {
        TurnQuestions questions = new TurnQuestions();
        assertThat(questions.attach("call")).isNull();
    }

    @Test
    void closingACompletedTurnEndsOpenQuestionsAndKeepsStatedOnes() {
        TurnQuestions questions = splitTwo();
        questions.apply(QuestionToolCatalog.TRACK, json("{\"id\":1,\"etat\":\"NON_ABOUTIE\"}"));

        questions.close(true);

        assertThat(questions.snapshot().questions()).extracting(TurnQuestions.QuestionView::state)
                .containsExactly("NON_ABOUTIE", "TERMINEE");
    }

    @Test
    void closingAnUnfinishedTurnMarksOpenQuestionsAsNotDone() {
        TurnQuestions questions = splitTwo();
        questions.apply(QuestionToolCatalog.TRACK, json("{\"id\":1,\"etat\":\"TERMINEE\"}"));

        questions.close(false);

        assertThat(questions.snapshot().questions()).extracting(TurnQuestions.QuestionView::state)
                .containsExactly("TERMINEE", "NON_ABOUTIE");
    }

    @Test
    void theSnapshotIsAttachedToTheReportWithoutTouchingTheRest() throws Exception {
        TurnQuestions questions = splitTwo();
        String json = TurnQuestions.attachTo("{\"inputTokens\":3}", questions.snapshot());

        JsonNode report = MAPPER.readTree(json);
        assertThat(report.path("inputTokens").asInt()).isEqualTo(3);
        assertThat(report.path("questions").path("questions")).hasSize(2);
        assertThat(report.path("questions").path("questions").get(0).path("libelle").asText())
                .isEqualTo("Pourquoi le build échoue");
        assertThat(TurnQuestions.attachTo("{\"a\":1}", null)).isEqualTo("{\"a\":1}");
        assertThat(TurnQuestions.attachTo("pas du json", questions.snapshot())).isEqualTo("pas du json");
        assertThat(TurnQuestions.attachTo(null, questions.snapshot())).contains("\"questions\"");
    }
}
