package fr.claudegateway.atelier.nextsteps;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.nextsteps.NextStep.Label;

class NextStepsBlockTest {

    private static String option(String title, String why, String label, String request) {
        return "{\"titre\":\"" + title + "\",\"pourquoi\":\"" + why + "\",\"etiquette\":\"" + label
                + "\",\"demande\":\"" + request + "\"}";
    }

    private static String block(String... options) {
        return "<<suites>>\n[" + String.join(",", options) + "]\n<</suites>>";
    }

    @Test
    void extractsTheStepsAndStripsTheBlock() {
        String raw = "C'est corrigé.\n\n" + block(
                option("Vérifier l'export", "Non vérifié", "Vérifier", "Vérifie l'export."),
                option("Étape 4", "Plan : étape 4", "Continuer", "Passe à l'étape 4."),
                option("PR", "Tests verts", "livrer", "Ouvre la PR."));

        NextStepsBlock.Extraction extraction = NextStepsBlock.extract(raw);

        assertThat(extraction.text()).isEqualTo("C'est corrigé.");
        assertThat(extraction.steps()).extracting(NextStep::label)
                .containsExactly(Label.VERIFIER, Label.CONTINUER, Label.LIVRER);
        assertThat(extraction.steps().get(0).request()).isEqualTo("Vérifie l'export.");
    }

    @Test
    void aTextWithoutBlockIsReturnedUnchanged() {
        String raw = "Réponse.\n\n\n\nAvec des blancs.";

        NextStepsBlock.Extraction extraction = NextStepsBlock.extract(raw);

        assertThat(extraction.text()).isSameAs(raw);
        assertThat(extraction.steps()).isEmpty();
        assertThat(NextStepsBlock.extract(null).steps()).isEmpty();
    }

    @Test
    void aMalformedBlockIsStrippedWithoutSteps() {
        NextStepsBlock.Extraction extraction = NextStepsBlock.extract("Fait.\n<<suites>>\n{oups\n<</suites>>");

        assertThat(extraction.text()).isEqualTo("Fait.");
        assertThat(extraction.steps()).isEmpty();
    }

    @Test
    void anOpeningWithoutClosingStripsEverythingAfterIt() {
        NextStepsBlock.Extraction extraction =
                NextStepsBlock.extract("Fait.\n<<suites>>\n[{\"titre\":\"tronqu");

        assertThat(extraction.text()).isEqualTo("Fait.");
        assertThat(extraction.steps()).isEmpty();
    }

    @Test
    void anInvalidOptionIsIgnoredAlone() {
        String raw = block(
                option("Sans motif", "", "Vérifier", "x"),
                option("Étiquette inconnue", "motif", "Plus tard", "x"),
                option("Sans demande", "motif", "Risque", ""),
                option("Valide", "motif", "risque", "Regarde le risque."));

        List<NextStep> steps = NextStepsBlock.extract(raw).steps();

        assertThat(steps).extracting(NextStep::title).containsExactly("Valide");
        assertThat(steps.get(0).label()).isEqualTo(Label.RISQUE);
    }

    @Test
    void atMostFourStepsAreKept() {
        String raw = block(option("1", "m", "Continuer", "a"), option("2", "m", "Continuer", "b"),
                option("3", "m", "Continuer", "c"), option("4", "m", "Continuer", "d"),
                option("5", "m", "Continuer", "e"));

        assertThat(NextStepsBlock.extract(raw).steps()).extracting(NextStep::title)
                .containsExactly("1", "2", "3", "4");
    }

    @Test
    void aLongTitleIsTruncatedAndATooLongRequestRejectsTheOption() {
        String longTitle = "t".repeat(200);
        String tooLong = "d".repeat(NextStepsBlock.MAX_REQUEST_CHARS + 1);
        String raw = block(option(longTitle, "m", "Continuer", "ok"), option("x", "m", "Continuer", tooLong));

        List<NextStep> steps = NextStepsBlock.extract(raw).steps();

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).title()).hasSizeLessThanOrEqualTo(NextStepsBlock.MAX_TITLE_CHARS).endsWith("…");
    }

    @Test
    void theLastReadableBlockWinsAndAllBlocksAreStripped() {
        String raw = "A\n" + block(option("premier", "m", "Continuer", "a")) + "\nB\n"
                + block(option("second", "m", "Vérifier", "b"));

        NextStepsBlock.Extraction extraction = NextStepsBlock.extract(raw);

        assertThat(extraction.text()).isEqualTo("A\n\nB");
        assertThat(extraction.steps()).extracting(NextStep::title).containsExactly("second");
    }

    @Test
    void aMarkdownFenceAroundTheJsonIsTolerated() {
        String raw = "<<suites>>\n```json\n[" + option("x", "m", "Continuer", "a") + "]\n```\n<</suites>>";

        assertThat(NextStepsBlock.extract(raw).steps()).hasSize(1);
    }

    @Test
    void labelsAreNormalizedFromAccentsAndCase() {
        assertThat(NextStepsBlock.label("vérifier")).isEqualTo(Label.VERIFIER);
        assertThat(NextStepsBlock.label(" LIVRER ")).isEqualTo(Label.LIVRER);
        assertThat(NextStepsBlock.label("autre")).isNull();
        assertThat(NextStepsBlock.label(null)).isNull();
    }

    @Test
    void attachToAddsTheStepsToTheReport() {
        List<NextStep> steps = List.of(new NextStep("t", "w", Label.CONTINUER, "r"));

        assertThat(NextStepsBlock.attachTo("{\"inputTokens\":3}", steps))
                .contains("\"inputTokens\":3").contains("\"nextSteps\":[{\"title\":\"t\"")
                .contains("\"label\":\"CONTINUER\"").contains("\"request\":\"r\"");
        assertThat(NextStepsBlock.attachTo(null, steps)).startsWith("{\"nextSteps\"");
        assertThat(NextStepsBlock.attachTo("{\"a\":1}", List.of())).isEqualTo("{\"a\":1}");
        assertThat(NextStepsBlock.attachTo(null, List.of())).isNull();
        assertThat(NextStepsBlock.attachTo("illisible", steps)).isEqualTo("illisible");
    }
}
