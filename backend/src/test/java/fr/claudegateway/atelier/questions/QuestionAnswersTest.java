package fr.claudegateway.atelier.questions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.nextsteps.ChantierAnchor;

/** F-210 / SF-210-02 — lire une réponse par question, et rendre un texte sans marqueur. */
class QuestionAnswersTest {

    private static final String SUITE = "<<suites>>\n[{\"titre\":\"Relancer le build\",\"pourquoi\":\"échec vu\","
            + "\"etiquette\":\"Vérifier\",\"demande\":\"Relance le build\"}]\n<</suites>>";

    private static TurnQuestions.Snapshot two() {
        return new TurnQuestions.Snapshot(List.of(
                new TurnQuestions.QuestionView(1, "Le build", null, "TERMINEE", List.of()),
                new TurnQuestions.QuestionView(2, "Le README", null, "TERMINEE", List.of())), null);
    }

    private static final java.util.function.Function<List<fr.claudegateway.atelier.nextsteps.NextStep>,
            ChantierAnchor.Anchored> PLAIN = steps -> ChantierAnchor.apply(steps, null);

    @Test
    void twoBlocksGiveTwoAnswersWithTheirOwnEssentialBodyAndSuites() {
        String raw = "<<question id=\"1\">>\n<<essentiel>>\nLe build casse sur un test.\n<</essentiel>>\n"
                + "Détail du build.\n" + SUITE + "\n<</question>>\n"
                + "<<question id=\"2\">>\n<<essentiel>>\nREADME à jour.\n<</essentiel>>\nDétail README.\n"
                + "<</question>>";

        List<QuestionAnswers.Answer> answers = QuestionAnswers.parse(raw, two(), PLAIN);

        assertThat(answers).hasSize(2);
        assertThat(answers.get(0).essential()).isEqualTo("Le build casse sur un test.");
        assertThat(answers.get(0).body()).isEqualTo("Détail du build.");
        assertThat(answers.get(0).nextSteps()).hasSize(1);
        assertThat(answers.get(0).nextSteps().get(0).title()).isEqualTo("Relancer le build");
        assertThat(answers.get(0).libelle()).isEqualTo("Le build");
        assertThat(answers.get(0).status()).isEqualTo("TERMINEE");
        assertThat(answers.get(1).essential()).isEqualTo("README à jour.");
        assertThat(answers.get(1).nextSteps()).isEmpty();
    }

    @Test
    void markersAreTolerantAndAnOpenBlockRunsToTheEnd() {
        String raw = "<< Question id=1 >>\nPremière.\n<< / question >>\n<<question 2>>\nSeconde tronquée";

        List<QuestionAnswers.Answer> answers = QuestionAnswers.parse(raw, two(), PLAIN);

        assertThat(answers).extracting(QuestionAnswers.Answer::body).containsExactly("Première.", "Seconde tronquée");
    }

    @Test
    void aQuestionWithoutBlockIsNotDoneAndUnknownIdsOrDuplicatesAreIgnored() {
        String raw = "<<question id=\"1\">>A<</question>><<question id=\"1\">>B<</question>>"
                + "<<question id=\"9\">>C<</question>>";

        List<QuestionAnswers.Answer> answers = QuestionAnswers.parse(raw, two(), PLAIN);

        assertThat(answers).extracting(QuestionAnswers.Answer::body).containsExactly("A", "");
        assertThat(answers.get(1).status()).isEqualTo("NON_ABOUTIE");
        assertThat(QuestionAnswers.unanswered(answers)).containsExactly(2);
    }

    @Test
    void aStatedNotDoneQuestionKeepsItsStatusAndSuitesNeedAnAnchor() {
        TurnQuestions.Snapshot snapshot = new TurnQuestions.Snapshot(List.of(
                new TurnQuestions.QuestionView(1, "A", null, "NON_ABOUTIE", List.of()),
                new TurnQuestions.QuestionView(2, "B", null, "TERMINEE", List.of())), null);
        String raw = "<<question id=\"1\">>Pas pu.\n" + SUITE + "<</question>><<question id=\"2\">>Oui<</question>>";

        List<QuestionAnswers.Answer> answers = QuestionAnswers.parse(raw, snapshot, null);

        assertThat(answers.get(0).status()).isEqualTo("NON_ABOUTIE");
        assertThat(answers.get(0).nextSteps()).isEmpty();
        assertThat(answers.get(0).body()).isEqualTo("Pas pu.");
    }

    @Test
    void nothingWithoutSplitFusionOrBlocks() {
        assertThat(QuestionAnswers.parse("<<question id=\"1\">>A<</question>>", null, PLAIN)).isEmpty();
        TurnQuestions.Snapshot fused = new TurnQuestions.Snapshot(two().questions(),
                new TurnQuestions.Fusion("la seconde remplace la première"));
        assertThat(QuestionAnswers.parse("<<question id=\"1\">>A<</question>>", fused, PLAIN)).isEmpty();
        assertThat(QuestionAnswers.parse("Une réponse sans bloc.", two(), PLAIN)).isEmpty();
    }

    @Test
    void theReadableTextHasNoQuestionMarkerAndOneEssentialAtMost() {
        String text = "<<question id=\"1\">>\n<<essentiel>>\nE1\n<</essentiel>>\nD1\n<</question>>\n"
                + "<<question id=\"2\">>\n<<essentiel>>\nE2\n<</essentiel>>\nD2\n<</question>>";

        String readable = QuestionAnswers.readable(text, two());

        assertThat(readable).doesNotContain("<<question").doesNotContain("<</question>>")
                .doesNotContain("<<essentiel>>").contains("**1. Le build**").contains("**2. Le README**")
                .contains("E1").contains("D2");
        assertThat(QuestionAnswers.readable("Texte simple.", two())).isEqualTo("Texte simple.");
        assertThat(QuestionAnswers.readable("<<question id=\"1\">>\n<<essentiel>>\nE\n<</essentiel>>", null))
                .contains("<<essentiel>>").contains("**1.**").doesNotContain("<<question");
    }
}
