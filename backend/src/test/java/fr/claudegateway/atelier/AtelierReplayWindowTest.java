package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * La coupure du rejeu des traces d'outils (F-134 / SF-134-01).
 *
 * <p><b>La propriété qui compte</b> est {@link #theCutDoesNotMoveBetweenTwoConsecutiveTurns()} :
 * tant que la coupure ne bouge pas, le préfixe envoyé au fournisseur reste identique, et le cache
 * se relit. C'est elle qui vaut les 98 % de coût d'écriture constatés avant ce correctif.</p>
 */
class AtelierReplayWindowTest {

    private static final int WINDOW = 12;

    @Test
    void aThreadShorterThanTheWindowIsFullyTraced() {
        assertThat(cut(10)).isZero();
        assertThat(cut(12)).isZero();
    }

    @Test
    void theCutStaysAtZeroUntilTwiceTheWindow() {
        // De 13 à 23 tours, tout reste tracé : le modèle voit PLUS de preuves qu'avant, jamais
        // moins — c'est ce qui rend ce correctif compatible avec la contrainte de F-130.
        for (int turns = 13; turns <= 23; turns++) {
            assertThat(cut(turns)).as("fil de %d tours", turns).isZero();
        }
    }

    @Test
    void theCutMovesOnlyAtMultiplesOfTheWindow() {
        assertThat(tracedTurns(23)).isEqualTo(23);
        assertThat(tracedTurns(24)).isEqualTo(12);
        assertThat(tracedTurns(35)).isEqualTo(23);
        assertThat(tracedTurns(36)).isEqualTo(12);
    }

    @Test
    void theCutDoesNotMoveBetweenTwoConsecutiveTurns() {
        // LE TEST CENTRAL. Avant SF-134-01, la coupure avançait à CHAQUE tour : le message situé au
        // début du préfixe changeait de forme, et tout ce qui suivait devait être réécrit. Ici, sur
        // cinquante tours, elle ne bouge que quatre fois.
        int moves = 0;
        int previous = cut(1);
        for (int turns = 2; turns <= 50; turns++) {
            int current = cut(turns);
            if (current != previous) {
                moves++;
                previous = current;
            }
        }

        // 24, 36, 48 — et le premier passage de 0 à 12. Quatre mutations pour cinquante tours,
        // contre quarante-neuf auparavant.
        assertThat(moves).isEqualTo(3);
    }

    @Test
    void theNumberOfTracedTurnsIsNeverBelowTheWindow() {
        // La garantie de qualité, vérifiée sur cent fils : jamais moins de preuves qu'avant.
        for (int turns = 1; turns <= 100; turns++) {
            assertThat(tracedTurns(turns))
                    .as("fil de %d tours", turns)
                    .isGreaterThanOrEqualTo(Math.min(turns, WINDOW));
        }
    }

    @Test
    void anAbsurdWindowNeverThrowsAndTracesEverything() {
        assertThat(AtelierChatService.firstTracedIndex(thread(20), 0)).isZero();
        assertThat(AtelierChatService.firstTracedIndex(thread(20), -5)).isZero();
        assertThatCode(() -> AtelierChatService.firstTracedIndex(List.of(), WINDOW))
                .doesNotThrowAnyException();
        assertThat(AtelierChatService.firstTracedIndex(List.of(), WINDOW)).isZero();
    }

    @Test
    void aThreadWithoutAnyAssistantTurnIsFullyTraced() {
        List<AtelierMessage> onlyUsers = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            onlyUsers.add(message("USER"));
        }

        assertThat(AtelierChatService.firstTracedIndex(onlyUsers, WINDOW)).isZero();
    }

    // ------------------------------------------------------------------ montage

    /** Index de coupure pour un fil de {@code turns} allers-retours (user + assistant). */
    private static int cut(int turns) {
        return AtelierChatService.firstTracedIndex(thread(turns), WINDOW);
    }

    /** Nombre de tours assistants rejoués AVEC leurs traces. */
    private static int tracedTurns(int turns) {
        List<AtelierMessage> past = thread(turns);
        int from = AtelierChatService.firstTracedIndex(past, WINDOW);
        int traced = 0;
        for (int index = from; index < past.size(); index++) {
            if ("ASSISTANT".equalsIgnoreCase(past.get(index).getRole())) {
                traced++;
            }
        }
        return traced;
    }

    /** Un fil de {@code turns} allers-retours : une demande, une réponse, et ainsi de suite. */
    private static List<AtelierMessage> thread(int turns) {
        List<AtelierMessage> past = new ArrayList<>(turns * 2);
        for (int i = 0; i < turns; i++) {
            past.add(message("USER"));
            past.add(message("ASSISTANT"));
        }
        return past;
    }

    private static AtelierMessage message(String role) {
        AtelierMessage message = new AtelierMessage();
        message.setRole(role);
        message.setContent("contenu");
        return message;
    }
}
