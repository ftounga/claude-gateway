package fr.claudegateway.runner.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Un travail d'extraction d'images</b> (F-190 / SF-190-01) : son état, ses images, ses manques.
 *
 * <p>Il est écrit dans {@code job.json}, <b>à côté de ses images</b> : un runner redémarré le relit,
 * et redemander la même vidéo rend ce qui a déjà été fait au lieu de tout recommencer.</p>
 */
public final class VideoJob {

    static final String FILE_NAME = "job.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Les étapes, dans l'ordre. Le libellé est ce que l'utilisateur lit dans le flux. */
    public enum Phase {
        OUTILLAGE("Préparation de ffmpeg"),
        EXTRACTION("Extraction des images"),
        TRI("Tri des images"),
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

    /** Une image retenue : son instant dans la vidéo et son chemin, tel que {@code read_file} le lit. */
    public record Frame(double offsetSeconds, String path) {

        public String at() {
            return clock(offsetSeconds);
        }
    }

    private final String id;
    private final String video;
    private final String mode;
    private final Path dir;
    private Phase phase = Phase.OUTILLAGE;
    private Instant startedAt = Instant.now();
    private Instant finishedAt;
    private int examined;
    private final List<Frame> frames = new ArrayList<>();
    private final List<String> gaps = new ArrayList<>();
    private String failure = "";
    private String remedy = "";

    VideoJob(String id, String video, String mode, Path dir) {
        this.id = id;
        this.video = video;
        this.mode = mode;
        this.dir = dir;
    }

    public synchronized String id() {
        return id;
    }

    public synchronized String video() {
        return video;
    }

    public synchronized String mode() {
        return mode;
    }

    public synchronized Path dir() {
        return dir;
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized boolean isOver() {
        return phase.isOver();
    }

    public synchronized int examined() {
        return examined;
    }

    public synchronized List<Frame> frames() {
        return List.copyOf(frames);
    }

    public synchronized List<String> gaps() {
        return List.copyOf(gaps);
    }

    public synchronized String failure() {
        return failure;
    }

    public synchronized String remedy() {
        return remedy;
    }

    synchronized void phase(Phase next) {
        this.phase = next;
        if (next.isOver()) {
            this.finishedAt = Instant.now();
        }
    }

    synchronized void harvested(int examinedCount, List<Frame> kept, List<String> missing) {
        this.examined = examinedCount;
        this.frames.clear();
        this.frames.addAll(kept);
        this.gaps.clear();
        this.gaps.addAll(missing);
    }

    synchronized void failed(String why, String how) {
        this.failure = why == null ? "" : why;
        this.remedy = how == null ? "" : how;
        phase(Phase.ECHOUE);
    }

    /** Une phrase d'état, pour le flux de l'outil. */
    public synchronized String describe() {
        return switch (phase) {
            case TERMINE -> frames.size() + " image" + plural(frames.size()) + " retenue"
                    + plural(frames.size()) + " sur " + examined + " extraite" + plural(examined)
                    + ".";
            case ECHOUE -> "Échec : " + failure + (remedy.isBlank() ? "" : " — " + remedy);
            default -> phase.label() + "…";
        };
    }

    // ------------------------------------------------------------------ persistance

    synchronized void save() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", id);
        root.put("video", video);
        root.put("mode", mode);
        root.put("phase", phase.name());
        root.put("startedAt", startedAt.toString());
        if (finishedAt != null) {
            root.put("finishedAt", finishedAt.toString());
        }
        root.put("examined", examined);
        ArrayNode list = root.putArray("frames");
        for (Frame frame : frames) {
            list.addObject().put("offsetSeconds", frame.offsetSeconds()).put("path", frame.path());
        }
        ArrayNode missing = root.putArray("gaps");
        gaps.forEach(missing::add);
        root.put("failure", failure);
        root.put("remedy", remedy);
        try {
            Files.createDirectories(dir);
            Path temp = dir.resolve(FILE_NAME + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), root);
            Files.move(temp, dir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // L'état en mémoire reste juste ; seule la reprise après redémarrage est perdue.
        }
    }

    /**
     * Relit le travail écrit dans ce dossier. Un travail resté « en cours » sur le disque a été
     * interrompu par un arrêt du runner : il est rendu en échec, avec le moyen de le relancer —
     * jamais comme s'il tournait encore.
     */
    static Optional<VideoJob> load(Path dir) {
        Path file = dir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            VideoJob job = new VideoJob(root.path("id").asText(), root.path("video").asText(),
                    root.path("mode").asText(), dir);
            job.startedAt = parseInstant(root.path("startedAt").asText(null), Instant.now());
            job.finishedAt = parseInstant(root.path("finishedAt").asText(null), null);
            job.examined = root.path("examined").asInt();
            for (JsonNode frame : root.path("frames")) {
                job.frames.add(new Frame(frame.path("offsetSeconds").asDouble(),
                        frame.path("path").asText()));
            }
            for (JsonNode gap : root.path("gaps")) {
                job.gaps.add(gap.asText());
            }
            job.failure = root.path("failure").asText("");
            job.remedy = root.path("remedy").asText("");
            Phase stored = parsePhase(root.path("phase").asText());
            if (stored.isOver()) {
                job.phase = stored;
            } else {
                job.failed("le runner s'est arrêté pendant ce travail",
                        "relance video_images avec restart à true");
            }
            return Optional.of(job);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Phase parsePhase(String name) {
        try {
            return Phase.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Phase.ECHOUE;
        }
    }

    private static Instant parseInstant(String value, Instant fallback) {
        try {
            return value == null ? fallback : Instant.parse(value);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** {@code HH:MM:SS} d'un instant en secondes. */
    static String clock(double seconds) {
        long total = Math.max(0L, Math.round(Math.floor(seconds)));
        return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60,
                total % 60);
    }

    private static String plural(int count) {
        return count > 1 ? "s" : "";
    }
}
