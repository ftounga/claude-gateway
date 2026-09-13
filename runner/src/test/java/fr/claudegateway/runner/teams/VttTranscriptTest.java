package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-108 / SF-108-05 — <b>une transcription WebVTT devient des répliques datées</b>.
 */
class VttTranscriptTest {

    static final Instant ORIGIN = Instant.parse("2026-09-10T09:00:00Z");

    static String sample() {
        try (InputStream in = VttTranscriptTest.class.getResourceAsStream(
                "/teams/recording-transcript.vtt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Répliques, locuteurs <v> et instants depuis l'origine")
    void cues_speakers_and_instants() {
        VttTranscript.Reading reading = VttTranscript.parse(sample(), ORIGIN);

        assertEquals(2, reading.cues().size());
        TeamsTranscriptCue first = reading.cues().get(0);
        assertEquals("Paul Durand", first.speakerDisplayName());
        assertEquals("Bonjour à tous, on commence par le plan.", first.text());
        assertEquals(ORIGIN.plusMillis(4_120), first.at());
        assertEquals(3_780, first.durationMs());
        assertEquals(ORIGIN.plusSeconds(62), reading.cues().get(1).at());
        assertEquals(0, reading.skipped());
    }

    @Test
    @DisplayName("Sans origine, ou sans en-tête WEBVTT : zéro réplique, jamais une date inventée")
    void no_origin_or_no_header_reads_nothing() {
        assertTrue(VttTranscript.parse(sample(), null).cues().isEmpty());
        VttTranscript.Reading notVtt = VttTranscript.parse("<html>refusé</html>", ORIGIN);
        assertTrue(notVtt.cues().isEmpty());
        assertEquals(1, notVtt.skipped());
    }

    @Test
    @DisplayName("Horodatage illisible : réplique écartée et comptée ; heures et virgules acceptées")
    void unreadable_timing_is_counted() {
        VttTranscript.Reading reading = VttTranscript.parse("WEBVTT\n\nxx --> yy\nquoi\n\n"
                + "01:00:00,500 --> 01:00:01,000\nbonjour\n", ORIGIN);

        assertEquals(1, reading.cues().size());
        assertEquals(1, reading.skipped());
        assertEquals(ORIGIN.plusMillis(3_600_500), reading.cues().get(0).at());
        assertEquals("", reading.cues().get(0).speakerDisplayName());
    }
}
