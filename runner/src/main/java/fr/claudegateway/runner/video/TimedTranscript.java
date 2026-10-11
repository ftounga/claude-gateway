package fr.claudegateway.runner.video;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fr.claudegateway.runner.teams.DocxText;
import fr.claudegateway.runner.teams.TeamsTranscriptCue;
import fr.claudegateway.runner.teams.VttTranscript;

/**
 * <b>Une transcription horodatée</b> (F-190 / SF-190-03), quelle qu'en soit la forme : WebVTT, SRT,
 * export Teams en {@code .docx}, ou texte.
 *
 * <p>Seul ce qui porte un <b>horodatage</b> sert : une réplique sans instant ne peut être posée à
 * côté d'aucune image. Une transcription sans aucun horodatage lisible rend zéro réplique — et
 * l'appelant le <b>dit</b>, au lieu d'aligner au hasard.</p>
 */
public final class TimedTranscript {

    /** Une réplique : son instant dans la vidéo, son locuteur s'il est connu, son texte. */
    public record Line(double offsetSeconds, String speaker, String text) {
    }

    /** « H:MM:SS », « M:SS », « MM:SS.mmm » — l'instant d'une réplique dans un texte libre. */
    private static final String CLOCK = "(\\d{1,2}(?::\\d{1,2}){1,2}(?:[.,]\\d+)?)";
    /** « début --> fin » (VTT, SRT, ancien export Teams). */
    private static final Pattern RANGE = Pattern.compile("^\\s*" + CLOCK + "\\s*-->\\s*" + CLOCK);
    /** « Nom Prénom   0:03 » (export Teams récent) ou une heure seule sur sa ligne. */
    private static final Pattern SPEAKER_THEN_CLOCK = Pattern.compile("^(.*?)\\s*" + CLOCK + "\\s*$");

    private TimedTranscript() {
    }

    /**
     * Lit le fichier selon son extension.
     *
     * @throws IOException si le fichier est illisible
     */
    static List<Line> read(Path file) throws IOException {
        if (Files.size(file) > VttTranscript.MAX_BYTES) {
            throw new IOException("transcription de plus de 5 Mo");
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx")) {
            return parseText(DocxText.plainText(file));
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (name.endsWith(".vtt") || text.stripLeading().startsWith("WEBVTT")) {
            return fromVtt(text);
        }
        if (name.endsWith(".srt")) {
            return fromVtt("WEBVTT\n\n" + text.replaceAll("(\\d{2}:\\d{2}:\\d{2}),(\\d{3})", "$1.$2"));
        }
        return parseText(text);
    }

    static List<Line> fromVtt(String text) {
        List<Line> lines = new ArrayList<>();
        for (TeamsTranscriptCue cue : VttTranscript.parse(text, Instant.EPOCH).cues()) {
            if (cue.isReadable()) {
                lines.add(new Line(cue.at().toEpochMilli() / 1000d, cue.speakerDisplayName(),
                        cue.text()));
            }
        }
        return List.copyOf(lines);
    }

    /**
     * Un texte libre : une ligne horodatée ouvre une réplique, les lignes suivantes en sont le
     * texte. Une heure citée au fil d'une phrase (« rendez-vous à 14:30 ») ne doit pas ouvrir de
     * réplique : le « locuteur » qui la précède doit ressembler à un nom — court, sans ponctuation
     * de phrase — et l'instant ne doit pas reculer.
     */
    static List<Line> parseText(String text) {
        List<Line> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        double current = -1d;
        String speaker = "";
        StringBuilder spoken = new StringBuilder();
        for (String raw : text.replace("\r\n", "\n").split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Opening opening = opening(line, current);
            if (opening != null) {
                flush(lines, current, speaker, spoken);
                current = opening.offset();
                speaker = opening.speaker();
                spoken.setLength(0);
                continue;
            }
            if (current >= 0d) {
                spoken.append(spoken.length() > 0 ? " " : "").append(line);
            }
        }
        flush(lines, current, speaker, spoken);
        return List.copyOf(lines);
    }

    private record Opening(double offset, String speaker) {
    }

    private static Opening opening(String line, double previous) {
        Matcher range = RANGE.matcher(line);
        if (range.find()) {
            double offset = seconds(range.group(1));
            return offset >= 0d ? new Opening(offset, "") : null;
        }
        Matcher named = SPEAKER_THEN_CLOCK.matcher(line);
        if (!named.matches()) {
            return null;
        }
        String who = named.group(1).strip();
        double offset = seconds(named.group(2));
        boolean looksLikeAName = who.length() <= 60 && who.split("\\s+").length <= 6
                && !who.matches(".*[.!?,;:«»\"]$") && !who.matches(".*\\d.*");
        if (offset < 0d || offset < previous || (!who.isEmpty() && !looksLikeAName)) {
            return null;
        }
        return new Opening(offset, who);
    }

    private static void flush(List<Line> lines, double offset, String speaker, StringBuilder spoken) {
        if (offset >= 0d && spoken.length() > 0) {
            lines.add(new Line(offset, speaker, spoken.toString()));
        }
    }

    /** « 1:02:03.5 », « 2:03 », « 0:0:3,25 » en secondes ; illisible → -1. */
    static double seconds(String clock) {
        try {
            String[] parts = clock.replace(',', '.').split(":");
            double total = 0d;
            for (String part : parts) {
                total = total * 60d + Double.parseDouble(part);
            }
            return total;
        } catch (NumberFormatException e) {
            return -1d;
        }
    }
}
