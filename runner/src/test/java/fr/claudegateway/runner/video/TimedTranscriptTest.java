package fr.claudegateway.runner.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** <b>Une transcription horodatée</b>, quelle qu'en soit la forme (F-190 / SF-190-03). */
class TimedTranscriptTest {

    @TempDir
    Path dir;

    @Test
    void un_vtt_rend_ses_repliques_avec_locuteur() throws IOException {
        Path file = Files.writeString(dir.resolve("demo.vtt"), """
                WEBVTT

                00:00:03.000 --> 00:00:06.000
                <v Mickaël>Voici le board principal.</v>

                00:01:10.500 --> 00:01:15.000
                <v Francky>Et le groupe RH ?</v>
                """);

        List<TimedTranscript.Line> lines = TimedTranscript.read(file);

        assertEquals(2, lines.size());
        assertEquals(3d, lines.get(0).offsetSeconds());
        assertEquals("Mickaël", lines.get(0).speaker());
        assertEquals("Voici le board principal.", lines.get(0).text());
        assertEquals(70.5d, lines.get(1).offsetSeconds());
    }

    @Test
    void un_srt_se_lit_comme_un_vtt() throws IOException {
        Path file = Files.writeString(dir.resolve("demo.srt"), """
                1
                00:00:03,250 --> 00:00:06,000
                Bonjour à tous.

                2
                00:02:00,000 --> 00:02:04,000
                On passe aux pentests.
                """);

        List<TimedTranscript.Line> lines = TimedTranscript.read(file);

        assertEquals(2, lines.size());
        assertEquals(3.25d, lines.get(0).offsetSeconds());
        assertEquals("On passe aux pentests.", lines.get(1).text());
    }

    @Test
    void lexport_teams_nom_puis_heure_ouvre_une_replique() {
        List<TimedTranscript.Line> lines = TimedTranscript.parseText("""
                Demo Monday
                Mickaël Martin   0:03
                Alors là tu as le board principal,
                avec les cinq groupes.
                Francky Tounga   1:02:03
                D'accord, rendez-vous à 14:30 pour la suite.
                """);

        assertEquals(2, lines.size());
        assertEquals("Mickaël Martin", lines.get(0).speaker());
        assertEquals(3d, lines.get(0).offsetSeconds());
        assertEquals("Alors là tu as le board principal, avec les cinq groupes.", lines.get(0).text());
        assertEquals(3723d, lines.get(1).offsetSeconds());
        assertTrue(lines.get(1).text().contains("rendez-vous à 14:30"),
                "une heure citée dans une phrase n'ouvre pas de réplique");
    }

    @Test
    void lancien_export_debut_fleche_fin_ouvre_une_replique() {
        List<TimedTranscript.Line> lines = TimedTranscript.parseText("""
                0:0:0.0 --> 0:0:4.120
                Mickaël Martin
                Bonjour.
                0:0:12.5 --> 0:0:15.0
                Francky Tounga
                Salut.
                """);

        assertEquals(2, lines.size());
        assertEquals(12.5d, lines.get(1).offsetSeconds());
        assertTrue(lines.get(0).text().contains("Bonjour."));
    }

    @Test
    void un_texte_sans_horodatage_ne_rend_aucune_replique() {
        assertTrue(TimedTranscript.parseText("Compte rendu de la démo.\nRien de daté ici.").isEmpty());
        assertTrue(TimedTranscript.parseText("").isEmpty());
    }

    @Test
    void les_heures_longues_se_lisent() {
        assertEquals(3723d, TimedTranscript.seconds("1:02:03"));
        assertEquals(123.5d, TimedTranscript.seconds("2:03,5"));
        assertEquals(-1d, TimedTranscript.seconds("x:y"));
    }
}
