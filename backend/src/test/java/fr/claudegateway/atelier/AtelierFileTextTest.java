package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Lecture numérotée/paginée et remplacement exact (F-39 / SF-39-06). Logique pure : elle vaut à
 * l'identique pour le stockage objet et pour la machine de l'utilisateur.
 */
class AtelierFileTextTest {

    private static String lines(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            text.append("ligne ").append(i).append('\n');
        }
        return text.toString();
    }

    @Test
    void everyLineCarriesItsNumber() {
        String page = AtelierFileText.numbered("alpha\nbeta", null, null);

        assertThat(page).isEqualTo("     1→alpha\n     2→beta\n");
    }

    @Test
    void aTrailingNewlineIsNotCountedAsAnExtraLine() {
        assertThat(AtelierFileText.numbered("alpha\n", null, null)).isEqualTo("     1→alpha\n");
    }

    @Test
    void paginationSaysWhereToAskForTheRest() {
        String page = AtelierFileText.numbered(lines(10), 3, 4);

        assertThat(page).startsWith("     3→ligne 3\n");
        assertThat(page).contains("     6→ligne 6\n");
        assertThat(page).doesNotContain("ligne 7\n");
        assertThat(page).endsWith("… (lignes 3 à 6 sur 10 ; relance read_file avec offset=7)");
    }

    @Test
    void theLastPageCarriesNoFooter() {
        String page = AtelierFileText.numbered(lines(5), 4, 10);

        assertThat(page).isEqualTo("     4→ligne 4\n     5→ligne 5\n");
    }

    @Test
    void aFileLongerThanTheCeilingIsCutAtTwoThousandLines() {
        String page = AtelierFileText.numbered(lines(2_500), null, null);

        assertThat(page).contains("  2000→ligne 2000\n");
        assertThat(page).doesNotContain("ligne 2001\n");
        assertThat(page).contains("relance read_file avec offset=2001");
    }

    @Test
    void invalidPaginationFallsBackOnTheDefaults() {
        assertThat(AtelierFileText.numbered("alpha", 0, -5)).isEqualTo("     1→alpha\n");
        assertThat(AtelierFileText.numbered("alpha", null, 99_999)).isEqualTo("     1→alpha\n");
    }

    @Test
    void anOffsetPastTheEndSaysHowManyLinesThereActuallyAre() {
        assertThatThrownBy(() -> AtelierFileText.numbered(lines(3), 10, null))
                .isInstanceOf(InvalidFilePathException.class)
                .hasMessageContaining("3 ligne(s)");
    }

    @Test
    void aVeryLongLineIsCutRatherThanFloodingTheTurn() {
        String page = AtelierFileText.numbered("x".repeat(2_500), null, null);

        assertThat(page).hasSize("     1→".length() + AtelierFileText.MAX_LINE_CHARS + 2);
        assertThat(page).endsWith("…\n");
    }

    @Test
    void anEmptyFileSaysSoRatherThanSayingNothing() {
        assertThat(AtelierFileText.numbered("", null, null)).isEqualTo(AtelierFileText.EMPTY);
        assertThat(AtelierFileText.numbered(null, null, null)).isEqualTo(AtelierFileText.EMPTY);
    }

    // --- remplacement exact -------------------------------------------------------------------

    @Test
    void aUniqueOccurrenceIsReplaced() {
        AtelierFileText.Edit edit = AtelierFileText.replace("const a = 1;", "1", "2", false);

        assertThat(edit.content()).isEqualTo("const a = 2;");
        assertThat(edit.replacements()).isEqualTo(1);
    }

    @Test
    void aReplacementIsLiteralAndNeverARegularExpression() {
        AtelierFileText.Edit edit = AtelierFileText.replace("a.b|c", "a.b|c", "ok", false);

        assertThat(edit.content()).isEqualTo("ok");
    }

    @Test
    void severalOccurrencesAreARefusalRatherThanARandomChoice() {
        assertThatThrownBy(() -> AtelierFileText.replace("x x x", "x", "y", false))
                .isInstanceOf(InvalidFilePathException.class)
                .hasMessageContaining("trouvé 3 fois");
    }

    @Test
    void replaceAllTakesThemAll() {
        AtelierFileText.Edit edit = AtelierFileText.replace("x x x", "x", "y", true);

        assertThat(edit.content()).isEqualTo("y y y");
        assertThat(edit.replacements()).isEqualTo(3);
    }

    @Test
    void anAbsentTextIsRefusedWithAnActionableMessage() {
        assertThatThrownBy(() -> AtelierFileText.replace("alpha", "beta", "gamma", false))
                .isInstanceOf(InvalidFilePathException.class)
                .hasMessageContaining("introuvable");
    }

    @Test
    void anEditThatChangesNothingIsRefused() {
        assertThatThrownBy(() -> AtelierFileText.replace("alpha", "alpha", "alpha", false))
                .isInstanceOf(InvalidFilePathException.class)
                .hasMessageContaining("Aucune modification");
    }

    @Test
    void anEmptyNewStringDeletesThePassage() {
        assertThat(AtelierFileText.replace("alpha beta", " beta", "", false).content()).isEqualTo("alpha");
    }
}
