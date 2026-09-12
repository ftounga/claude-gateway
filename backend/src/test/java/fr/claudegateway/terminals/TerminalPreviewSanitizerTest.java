package fr.claudegateway.terminals;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Les <b>bornes de l'aperçu</b> (F-76 / SF-76-01).
 *
 * <p>Ce qui se vérifie ici n'a besoin d'aucune base : qu'une sortie de terminal réelle — colorée,
 * longue, bavarde — devienne six lignes lisibles dans une tuile. Et que la borne soit <b>tenue</b>,
 * pas suggérée : l'écran tronque déjà pour ne pas envoyer dix kilo-octets toutes les cinq secondes,
 * mais une borne tenue par l'appelant décrit le client d'aujourd'hui, pas ce que la table
 * accepte.</p>
 */
class TerminalPreviewSanitizerTest {

    private static final String ESC = Character.toString(27);

    @Test
    void onlyTheLastLinesSurvive() {
        // Un aperçu dit OÙ L'ON EN EST : ce sont les dernières lignes qui le disent, jamais les
        // premières. Garder le début d'un `npm test` montrerait éternellement « Running tests… ».
        List<String> many = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            many.add("ligne " + i);
        }

        List<String> kept = TerminalPreviewSanitizer.lines(many);

        assertThat(kept).hasSize(TerminalPreviewSanitizer.MAX_LINES);
        assertThat(kept.getLast()).isEqualTo("ligne 50");
        assertThat(kept.getFirst()).isEqualTo("ligne 45");
    }

    @Test
    void aVeryLongLineIsCutToWhatATileCanShow() {
        String enormous = "x".repeat(4_000);

        assertThat(TerminalPreviewSanitizer.line(enormous))
                .hasSize(TerminalPreviewSanitizer.MAX_LINE_LENGTH);
    }

    @Test
    void theActivityDetailHasItsOwnShorterBound() {
        assertThat(TerminalPreviewSanitizer.detail("npm test")).isEqualTo("npm test");
        assertThat(TerminalPreviewSanitizer.detail("y".repeat(500)))
                .hasSize(TerminalPreviewSanitizer.MAX_DETAIL_LENGTH);
    }

    @Test
    void ansiColoursAreRemovedRatherThanShown() {
        // Un émulateur de terminal les interprète ; une tuile HTML les afficherait telles quelles,
        // et c'est précisément là qu'on veut lire d'un coup d'œil.
        String coloured = ESC + "[31mÉCHEC" + ESC + "[0m 3 tests";

        assertThat(TerminalPreviewSanitizer.line(coloured)).isEqualTo("ÉCHEC 3 tests");
    }

    @Test
    void controlCharactersDoNotReachTheTile() {
        // Le retour chariot d'une barre de progression et la cloche d'un échec n'ont aucun sens
        // hors d'un terminal.
        String noisy = "téléchargement\r 100%";

        assertThat(TerminalPreviewSanitizer.line(noisy)).isEqualTo("téléchargement 100%");
    }

    @Test
    void aLineThatIsNothingButNoiseIsDropped() {
        // Une ligne vide dans une tuile est une ligne perdue : six lignes, ça se compte.
        assertThat(TerminalPreviewSanitizer.line(ESC + "[2K\r")).isNull();
        assertThat(TerminalPreviewSanitizer.lines(List.of(ESC + "[2K", "   ", "réel")))
                .containsExactly("réel");
    }

    @Test
    void nothingIsNeverAnError() {
        // L'aperçu est du décor : il ne doit jamais faire échouer le battement de cœur qui, lui,
        // tient la place.
        assertThat(TerminalPreviewSanitizer.lines(null)).isEmpty();
        assertThat(TerminalPreviewSanitizer.lines(List.of())).isEmpty();
        assertThat(TerminalPreviewSanitizer.line(null)).isNull();
        assertThat(TerminalPreviewSanitizer.detail(null)).isNull();
        assertThat(TerminalPreviewSanitizer.detail("   ")).isNull();
    }
}
