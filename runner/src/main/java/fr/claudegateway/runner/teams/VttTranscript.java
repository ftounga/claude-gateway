package fr.claudegateway.runner.teams;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Une transcription WebVTT</b>, lue en répliques (F-108 / SF-108-05).
 *
 * <p>Le format est un standard public (W3C WebVTT) ; la transcription que Teams exporte l'emploie avec
 * le locuteur en balise de voix : {@code <v Paul Durand>Bonjour à tous.</v>}. Les horodatages sont des
 * <b>décalages</b> depuis le début de l'enregistrement : ils deviennent des instants par l'origine
 * donnée — la même que celle des répliques Teams (le début de réunion observé), pour que l'alignement
 * F-90 garde une seule hypothèse.</p>
 *
 * <p><b>Forme éprouvée sur documentation, à confirmer sur poste réel.</b> Une réplique illisible est
 * écartée et comptée, jamais devinée.</p>
 */
public final class VttTranscript {

    /** Taille au-delà de laquelle on ne lit pas : ce n'est plus une transcription. */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    private static final Pattern TIMING = Pattern.compile(
            "((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})\\s+-->\\s+((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})");
    private static final Pattern VOICE = Pattern.compile("<v(?:\\.[^\\s>]*)?\\s+([^>]*)>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private VttTranscript() {
    }

    /** Ce qui a été lu, et ce qui a été écarté. */
    public record Reading(List<TeamsTranscriptCue> cues, int skipped) {
    }

    /**
     * Lit un texte WebVTT.
     *
     * @param origin instant du début de l'enregistrement ; {@code null} rend zéro réplique (on ne
     *               date pas sans origine)
     */
    public static Reading parse(String text, Instant origin) {
        List<TeamsTranscriptCue> cues = new ArrayList<>();
        int skipped = 0;
        if (text == null || origin == null || !text.stripLeading().startsWith("WEBVTT")) {
            return new Reading(List.of(), text == null || text.isBlank() ? 0 : 1);
        }
        String[] blocks = text.replace("\r\n", "\n").split("\n\\s*\n");
        for (String block : blocks) {
            String[] lines = block.strip().split("\n");
            int timingLine = -1;
            for (int index = 0; index < lines.length; index++) {
                if (lines[index].contains("-->")) {
                    timingLine = index;
                    break;
                }
            }
            if (timingLine < 0) {
                continue; // en-tête, NOTE, STYLE : pas une réplique
            }
            Matcher timing = TIMING.matcher(lines[timingLine]);
            if (!timing.find()) {
                skipped++;
                continue;
            }
            long start = millis(timing.group(1));
            long end = millis(timing.group(2));
            StringBuilder raw = new StringBuilder();
            for (int index = timingLine + 1; index < lines.length; index++) {
                raw.append(index > timingLine + 1 ? " " : "").append(lines[index].strip());
            }
            String payload = raw.toString();
            Matcher voice = VOICE.matcher(payload);
            String speaker = voice.find() ? voice.group(1).strip() : "";
            String spoken = TAG.matcher(payload).replaceAll("").replace("&amp;", "&")
                    .replace("&lt;", "<").replace("&gt;", ">").strip();
            if (start < 0 || spoken.isEmpty()) {
                skipped++;
                continue;
            }
            cues.add(new TeamsTranscriptCue(origin.plus(Duration.ofMillis(start)),
                    end >= start ? end - start : -1, "", speaker, spoken));
        }
        return new Reading(List.copyOf(cues), skipped);
    }

    /** « 01:02:03.456 » ou « 02:03.456 » en millisecondes ; illisible → -1. */
    static long millis(String value) {
        try {
            String[] parts = value.replace(',', '.').split(":");
            double seconds = Double.parseDouble(parts[parts.length - 1]);
            long minutes = Long.parseLong(parts[parts.length - 2]);
            long hours = parts.length == 3 ? Long.parseLong(parts[0]) : 0L;
            return Math.round(((hours * 60 + minutes) * 60 + seconds) * 1000);
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
