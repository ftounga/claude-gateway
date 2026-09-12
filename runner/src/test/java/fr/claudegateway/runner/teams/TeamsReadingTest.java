package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-87 / SF-87-01 — l'enveloppe de toute lecture : <b>ce qui a été lu, et ce qui ne l'a pas été</b>.
 *
 * <p>« 47 messages lus, 3 non reconnus, du 5 au 12 septembre » : la phrase du cadrage est ici un
 * test, parce qu'elle est la seule chose qui empêche un compte rendu plausible et faux.</p>
 */
class TeamsReadingTest {

    private static final TeamsReadWindow WINDOW = new TeamsReadWindow(null, null,
            Instant.parse("2026-09-05T09:00:00Z"), Instant.parse("2026-09-12T09:00:00Z"), 500,
            false, true);

    @Test
    @DisplayName("La phrase du cadrage est écrite telle quelle")
    void writes_the_sentence_of_the_framing_document() {
        TeamsReading<String> reading = new TeamsReading<>(fortySeven(),
                List.of(new TeamsGap(TeamsGapKind.UNKNOWN_MESSAGE_KIND, "", "", 3)), WINDOW,
                TeamsHealth.full(7));

        String summary = reading.summary("messages");

        assertTrue(summary.startsWith("47 messages lus, 3 non lus, du 5"), summary);
        assertTrue(summary.contains("septembre"), summary);
        assertTrue(summary.contains("genre de message inconnu"), summary);
    }

    @Test
    @DisplayName("Une lecture n'est complète que si RIEN n'a manqué")
    void complete_only_when_nothing_was_missed() {
        TeamsReading<String> clean = new TeamsReading<>(List.of("a"), List.of(), WINDOW,
                TeamsHealth.full(7));
        assertTrue(clean.complete());

        assertFalse(clean.withGap(TeamsGap.of(TeamsGapKind.MISSING_FIELD, "ici", "id")).complete());
    }

    @Test
    @DisplayName("Le plafond atteint suffit à rendre une lecture incomplète, même sans manque")
    void a_reached_cap_is_an_incompleteness() {
        TeamsReadWindow capped = new TeamsReadWindow(null, null,
                Instant.parse("2026-09-05T09:00:00Z"), Instant.parse("2026-09-12T09:00:00Z"), 10,
                true, false);
        TeamsReading<String> reading =
                new TeamsReading<>(List.of("a"), List.of(), capped, TeamsHealth.full(7));

        assertFalse(reading.complete());
        assertTrue(reading.summary("messages").contains("Plafond de 10 atteint"));
    }

    @Test
    @DisplayName("Deux manques identiques se cumulent au lieu de se répéter")
    void identical_gaps_are_folded() {
        TeamsReading<String> reading = new TeamsReading<String>(List.of(), List.of(), WINDOW,
                TeamsHealth.full(7))
                .withGap(TeamsGap.of(TeamsGapKind.MISSING_FIELD, "conversation 19:x", "id"))
                .withGap(TeamsGap.of(TeamsGapKind.MISSING_FIELD, "conversation 19:x", "id"))
                .withGap(TeamsGap.of(TeamsGapKind.MISSING_FIELD, "conversation 19:y", "id"));

        assertEquals(2, reading.gaps().size());
        assertEquals(3, reading.missedCount());
        assertTrue(reading.gaps().get(0).describe().startsWith("2 champ"));
    }

    @Test
    @DisplayName("Une santé dégradée s'écrit dans le résumé : on travaille, et on le dit")
    void a_degraded_health_is_written_in_every_summary() {
        TeamsReading<String> reading = new TeamsReading<>(List.of("a"), List.of(), WINDOW,
                TeamsHealth.of(3, 7, List.of("mentions"), List.of("v1"), ""));

        assertTrue(reading.summary("messages").contains("Teams a changé"));
        assertTrue(reading.summary("messages").contains("3 champs reconnus sur 7"));
        assertTrue(reading.summary("messages").contains("Version observée : v1"));
    }

    private static List<String> fortySeven() {
        return java.util.stream.IntStream.range(0, 47).mapToObj(Integer::toString).toList();
    }
}
