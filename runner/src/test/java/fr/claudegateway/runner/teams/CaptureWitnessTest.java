package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Le témoin au premier plan</b> (F-91 / SF-91-02, garde-fou n° 3).
 *
 * <p><b>Ce qui est éprouvé ici, et ce qui ne l'est pas.</b> Le CI est <b>sans écran</b> : aucune
 * fenêtre n'y est jamais construite. Ce qui est prouvé est donc ce que le témoin <b>dit</b> (son
 * texte, la durée qu'il affiche) et — l'essentiel — son <b>refus</b> quand il n'y a pas
 * d'environnement graphique, puisque ce refus devient un refus de capturer. Ce qui n'est <b>pas</b>
 * prouvé : qu'une fenêtre Swing reste effectivement au-dessus d'un partage d'écran Teams en plein
 * écran, sur chacun des trois systèmes. Le premier branchement tranchera.</p>
 */
@DisplayName("F-91 / SF-91-02 — le témoin au premier plan")
class CaptureWitnessTest {

    private static final Instant AT = Instant.parse("2026-09-13T14:32:00Z");

    @Test
    @DisplayName("aucun environnement graphique : REFUS, et le refus dit pourquoi c'est un refus")
    void headlessRefuses() {
        CaptureWitness witness = new CaptureWitness(() -> AT, () -> true);

        CaptureWitnessException refused =
                assertThrows(CaptureWitnessException.class, witness::requireAvailable);

        assertTrue(refused.getMessage().contains("Je ne capture pas"), refused.getMessage());
        assertTrue(refused.getMessage().contains("capture oubliée"), refused.getMessage());
        assertTrue(refused.remedy().contains("session graphique"), refused.remedy());
    }

    @Test
    @DisplayName("montrer un témoin sans écran lève aussi : on ne contourne pas requireAvailable")
    void showAlsoRefusesWhenHeadless() {
        CaptureWitness witness = new CaptureWitness(() -> AT, () -> true);

        assertThrows(CaptureWitnessException.class,
                () -> witness.show(record(CapturePurpose.SELF_SCREEN), () -> { }));
    }

    @Test
    @DisplayName("effacer un témoin qui n'existe pas ne lève pas")
    void hideIsSafe() {
        new CaptureWitness(() -> AT, () -> true).hide();
    }

    @Test
    @DisplayName("le texte du témoin nomme l'usage : on ne confond pas une démo et une réunion")
    void headlineNamesThePurpose() {
        assertTrue(CaptureWitness.headline(record(CapturePurpose.MEETING_WITH_OTHERS))
                .contains("une réunion à plusieurs"));
        assertTrue(CaptureWitness.headline(record(CapturePurpose.SELF_SCREEN))
                .contains("mon propre écran"));
        assertTrue(CaptureWitness.headline(record(CapturePurpose.SELF_SCREEN))
                .contains("ENREGISTREMENT"));
    }

    @Test
    @DisplayName("la durée affichée court : c'est elle qui empêche la capture oubliée")
    void elapsedTicks() {
        CaptureRecord record = record(CapturePurpose.SELF_SCREEN);

        assertEquals("00:00:00", CaptureRecord.clock(record.elapsed(AT)));
        assertEquals("00:00:05", CaptureRecord.clock(record.elapsed(AT.plusSeconds(5))));
        assertEquals("01:05:00", CaptureRecord.clock(record.elapsed(AT.plusSeconds(3_900))));
    }

    @Test
    @DisplayName("le plafond est DIT, pas découvert : une limite qu'on heurte est une panne")
    void ceilingIsAnnounced() {
        String sentence = new CaptureCeiling().sentence();

        assertTrue(sentence.contains("03:00:00"), sentence);
        assertTrue(sentence.contains("s'arrêtera d'elle-même"), sentence);
        assertEquals(Duration.ofHours(3), CaptureCeiling.MAX);
    }

    private static CaptureRecord record(CapturePurpose purpose) {
        return new CaptureRecord("a1b2", new CaptureConsent(purpose, true), "/tmp/capture.mp4",
                new Watermark("francky", AT, purpose)).startedAt(AT);
    }
}
