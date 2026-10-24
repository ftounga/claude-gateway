package fr.claudegateway.runner.video.render;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Un rendu vidéo</b> (F-215 / SF-215-02) : son étape, son processus détaché, sa sortie, son échec et le
 * remède. Écrit dans {@code job.json} à côté de son journal {@code render.log} : un runner redémarré le relit,
 * et le suivi continue — le rendu, lui, n'a jamais dépendu du runner.
 */
public final class RenderJob {

    static final String FILE_NAME = "job.json";
    static final String LOG_NAME = "render.log";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3}(?:[.,]\\d+)?)\\s?%");
    private static final Pattern FRAMES = Pattern.compile("(?i)frames?\\s+(\\d+)\\s*/\\s*(\\d+)");

    /** Les étapes, dans l'ordre. Le libellé est ce que l'agent et l'utilisateur lisent. */
    public enum Phase {
        PREPARATION("Préparation de la chaîne (Node 22, hyperframes " + RenderToolchain.HYPERFRAMES_VERSION
                + ", Chrome sans écran, ffmpeg)"),
        RENDU("Rendu en cours"),
        TERMINE("Terminé"),
        ECHOUE("Échec");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean isOver() {
            return this == TERMINE || this == ECHOUE;
        }
    }

    private final String id;
    private final Path dir;
    private final String source;
    private final String output;
    private final Path outputPath;
    private final boolean checkOnly;
    private Phase phase = Phase.PREPARATION;
    private Instant startedAt = Instant.now();
    private Instant finishedAt;
    private long pid = -1;
    private String report = "";
    private String failure = "";
    private String remedy = "";

    RenderJob(String id, Path dir, String source, String output, Path outputPath, boolean checkOnly) {
        this.id = id;
        this.dir = dir;
        this.source = source == null ? "" : source;
        this.output = output == null ? "" : output;
        this.outputPath = outputPath;
        this.checkOnly = checkOnly;
    }

    public synchronized String id() {
        return id;
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized long pid() {
        return pid;
    }

    public synchronized Path outputPath() {
        return outputPath;
    }

    public synchronized boolean checkOnly() {
        return checkOnly;
    }

    public synchronized String failure() {
        return failure;
    }

    public synchronized String remedy() {
        return remedy;
    }

    public synchronized String report() {
        return report;
    }

    Path log() {
        return dir.resolve(LOG_NAME);
    }

    synchronized void prepared(String report) {
        this.report = report == null ? "" : report;
    }

    synchronized void rendering(long pid) {
        this.pid = pid;
        this.phase = Phase.RENDU;
    }

    synchronized void done() {
        phase = Phase.TERMINE;
        finishedAt = Instant.now();
    }

    synchronized void fail(String failure, String remedy) {
        phase = Phase.ECHOUE;
        this.failure = failure == null ? "" : failure;
        this.remedy = remedy == null ? "" : remedy;
        finishedAt = Instant.now();
    }

    /**
     * Relit l'état d'un rendu en cours depuis son journal et son processus.
     *
     * @param alive vrai si le processus du rendu tourne encore
     */
    synchronized void refresh(boolean alive) {
        if (phase != Phase.RENDU) {
            return;
        }
        String tail = tailOfLog(64_000);
        if (tail.contains("Render failed")) {
            fail("Le rendu a échoué : " + lastLines(tail, 4), "Corrige la composition (lint, snapshot) puis relance "
                    + "video_render.");
            return;
        }
        boolean produced = outputSize() > 0;
        if (tail.contains("Render complete") && produced) {
            done();
            return;
        }
        if (!alive) {
            if (produced && !tail.contains("Render failed")) {
                done();
            } else {
                fail("Le rendu s'est arrêté sans produire de vidéo : " + lastLines(tail, 4),
                        "Relis les dernières lignes ci-dessus, corrige, puis relance video_render.");
            }
        }
    }

    /** L'avancement lu dans le journal : dernier pourcentage, sinon {@code frame n/m}, sinon vide. */
    synchronized String progress() {
        String tail = tailOfLog(16_000);
        String percent = null;
        Matcher p = PERCENT.matcher(tail);
        while (p.find()) {
            percent = p.group(1).replace(',', '.');
        }
        if (percent != null) {
            return percent + " %";
        }
        String frames = null;
        Matcher f = FRAMES.matcher(tail);
        while (f.find()) {
            frames = "image " + f.group(1) + " / " + f.group(2);
        }
        return frames == null ? "" : frames;
    }

    synchronized long outputSize() {
        try {
            return outputPath != null && Files.isRegularFile(outputPath) ? Files.size(outputPath) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    synchronized String lastLogLines(int lines) {
        return lastLines(tailOfLog(16_000), lines);
    }

    synchronized Duration elapsed() {
        return Duration.between(startedAt, finishedAt == null ? Instant.now() : finishedAt);
    }

    private String tailOfLog(int maxBytes) {
        Path log = log();
        if (!Files.isRegularFile(log)) {
            return "";
        }
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            long from = Math.max(0, length - maxBytes);
            byte[] bytes = new byte[(int) (length - from)];
            file.seek(from);
            file.readFully(bytes);
            // Les barres de progression réécrivent la ligne par \r : chaque état devient une ligne.
            return new String(bytes, StandardCharsets.UTF_8).replace('\r', '\n');
        } catch (IOException e) {
            return "";
        }
    }

    static String lastLines(String text, int count) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            String clean = line.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").strip();
            if (!clean.isEmpty()) {
                lines.add(clean);
            }
        }
        int from = Math.max(0, lines.size() - count);
        return String.join(" | ", lines.subList(from, lines.size()));
    }

    // ------------------------------------------------------------------ persistance

    synchronized void save() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", id);
        root.put("source", source);
        root.put("output", output);
        root.put("output_path", outputPath == null ? "" : outputPath.toString());
        root.put("check_only", checkOnly);
        root.put("phase", phase.name());
        root.put("started_at", startedAt.toString());
        root.put("finished_at", finishedAt == null ? "" : finishedAt.toString());
        root.put("pid", pid);
        root.put("report", report);
        root.put("failure", failure);
        root.put("remedy", remedy);
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(FILE_NAME + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Le suivi en mémoire continue ; seul le redémarrage du runner perdrait la trace.
        }
    }

    static Optional<RenderJob> load(Path dir) {
        Path file = dir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            String outputPath = root.path("output_path").asText("");
            RenderJob job = new RenderJob(root.path("id").asText(""), dir, root.path("source").asText(""),
                    root.path("output").asText(""), outputPath.isEmpty() ? null : Path.of(outputPath),
                    root.path("check_only").asBoolean(false));
            job.phase = Phase.valueOf(root.path("phase").asText(Phase.ECHOUE.name()));
            job.startedAt = Instant.parse(root.path("started_at").asText(Instant.now().toString()));
            String finished = root.path("finished_at").asText("");
            job.finishedAt = finished.isEmpty() ? null : Instant.parse(finished);
            job.pid = root.path("pid").asLong(-1);
            job.report = root.path("report").asText("");
            job.failure = root.path("failure").asText("");
            job.remedy = root.path("remedy").asText("");
            if (job.phase == Phase.PREPARATION) {
                // La préparation vivait dans l'ancien runner : elle est morte avec lui.
                job.fail("La préparation a été interrompue (runner redémarré).", "Relance video_render.");
            }
            return Optional.of(job);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    synchronized String source() {
        return source;
    }

    synchronized String output() {
        return output;
    }
}
