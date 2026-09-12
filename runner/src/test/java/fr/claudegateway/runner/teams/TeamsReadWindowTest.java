package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-87 / SF-87-01 — le plafond de remontée (décision D4) : <b>annoncé, négociable, jamais
 * silencieux</b>, et le résultat porte toujours la fenêtre réellement lue.
 */
class TeamsReadWindowTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");

    @Test
    @DisplayName("Le plafond par défaut est une semaine et 500 messages, et il est nommé")
    void default_cap_is_one_week_and_five_hundred() {
        TeamsReadWindow window = TeamsReadWindow.standard(NOW);

        assertEquals(NOW.minus(Duration.ofDays(7)), window.requestedFrom());
        assertEquals(500, window.cap());
        assertEquals(TeamsReadWindow.DEFAULT_DAYS, 7);
        assertEquals(TeamsReadWindow.DEFAULT_MAX_MESSAGES, 500);
    }

    @Test
    @DisplayName("Une fenêtre négociée dans la demande remplace le défaut")
    void a_negotiated_window_replaces_the_default() {
        TeamsReadWindow window = new TeamsReadWindow(Instant.parse("2026-09-01T00:00:00Z"), NOW,
                null, null, 2_000, false, false);

        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), window.requestedFrom());
        assertEquals(2_000, window.cap());
    }

    @Test
    @DisplayName("Le plafond atteint rend la fenêtre incomplète — c'est un fait, pas un détail")
    void a_reached_cap_is_never_full_coverage() {
        TeamsReadWindow covered = TeamsReadWindow.standard(NOW)
                .covering(NOW.minus(Duration.ofDays(2)), NOW, true, false);

        assertTrue(covered.capReached());
        assertFalse(covered.fullyCovered());
    }

    @Test
    @DisplayName("Atteindre le début de la conversation couvre la fenêtre, même remontée plus court")
    void reaching_the_start_covers_the_window() {
        TeamsReadWindow covered = TeamsReadWindow.standard(NOW)
                .covering(NOW.minus(Duration.ofDays(2)), NOW, false, true);

        assertTrue(covered.fullyCovered(), "la conversation n'a rien de plus ancien à lire");
    }

    @Test
    @DisplayName("Une fenêtre non couverte le dit, même sans manque de lecture")
    void an_uncovered_window_says_so() {
        TeamsReadWindow covered = TeamsReadWindow.standard(NOW)
                .covering(NOW.minus(Duration.ofDays(2)), NOW, false, false);

        assertFalse(covered.fullyCovered());
    }

    @Test
    @DisplayName("La période réellement lue s'écrit en français")
    void describes_the_period_in_french() {
        TeamsReadWindow covered = TeamsReadWindow.standard(NOW)
                .covering(Instant.parse("2026-09-05T12:00:00Z"),
                        Instant.parse("2026-09-12T12:00:00Z"), false, true);

        assertTrue(covered.describe().contains("septembre"), covered.describe());
        assertEquals("aucune période lue", TeamsReadWindow.standard(NOW).describe());
    }
}
