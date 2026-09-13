package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Le dossier de dépôt</b> (F-100 / SF-100-05) : les enregistrements hors Teams — téléphone, salle, autre
 * outil de visio — déposés dans {@code <racine>/radar/depot/}, transcrits <b>sur la machine</b> par le moteur
 * de F-91, et dont <b>seul le texte</b> remonte.
 *
 * <p>L'enregistrement, son audio, les fichiers de travail : rien de cela ne quitte la machine, et le fichier
 * déposé n'est ni déplacé ni supprimé — le Radar lit, il ne range pas la machine de l'utilisateur.</p>
 */
final class RadarDepositCollector {

    static final List<String> EXTENSIONS = List.of(".mp3", ".m4a", ".wav", ".ogg", ".aac", ".flac", ".mp4", ".mov",
            ".mkv", ".webm");
    /** Enregistrements transcrits au plus par synchro : la transcription est longue. */
    static final int MAX_PER_SYNC = 3;
    /** Un fichier modifié plus récemment est peut-être en cours de copie. */
    static final Duration STABLE_AFTER = Duration.ofMinutes(2);
    /** Au-delà, une transcription est comptée échouée. */
    static final Duration MAX_TRANSCRIPTION = Duration.ofHours(4);
    static final long POLL_MS = 2_000L;

    private static final Pattern DATED_NAME = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2})[ _T-]+(\\d{1,2})[hH:.](\\d{2})(?:\\s*[-_–]\\s*(.+))?$");

    /** Ce que la transcription locale offre à la collecte — une interface, pour s'éprouver sans moteur. */
    interface Transcriber {

        /** Lance ou reprend la transcription d'un fichier ; rend le travail. */
        TranscriptionJob start(String id, Path file, Instant startedAt);
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path depot;
    private final Transcriber transcriber;
    private final Supplier<Instant> clock;
    private final BrowserLink.Sleeper sleeper;

    /**
     * @param transcriber {@code null} quand ce runner n'a pas de transcription locale : c'est <b>dit</b>
     */
    RadarDepositCollector(Path depot, Transcriber transcriber, Supplier<Instant> clock, BrowserLink.Sleeper sleeper) {
        this.depot = depot;
        this.transcriber = transcriber;
        this.clock = clock == null ? Instant::now : clock;
        this.sleeper = sleeper;
    }

    /** Ce que le relevé du dossier a donné. */
    static final class Report {
        final ObjectNode depot;
        final List<ObjectNode> threads = new ArrayList<>();
        int batches;
        int messages;
        boolean incomplete;
        boolean stopped;

        Report(ObjectNode depot) {
            this.depot = depot;
        }
    }

    /** Relève le dossier, transcrit, fait remonter. */
    Report collect(RadarAssignment assignment, RadarSyncContext context) {
        ObjectNode counts = mapper.createObjectNode();
        Report report = new Report(counts);
        int found = 0;
        int transcribed = 0;
        int skipped = 0;
        int deferred = 0;
        int failed = 0;
        int unavailable = 0;
        List<Path> files;
        try {
            files = candidates();
            counts.put("readable", true);
        } catch (IOException e) {
            counts.put("readable", false);
            files = List.of();
        }
        Set<String> done = done(assignment.input());
        List<Path> due = new ArrayList<>();
        for (Path file : files) {
            found++;
            if (done.contains(referenceOf(file))) {
                skipped++;
            } else {
                due.add(file);
            }
        }
        if (due.size() > MAX_PER_SYNC) {
            deferred = due.size() - MAX_PER_SYNC;
            report.incomplete = true;
            due = due.subList(0, MAX_PER_SYNC);
        }
        for (int index = 0; index < due.size(); index++) {
            if (!context.progress("depot", index, due.size())) {
                report.stopped = true;
                break;
            }
            Path file = due.get(index);
            String ref = referenceOf(file);
            Recording recording = describe(file);
            if (transcriber == null) {
                unavailable++;
                report.incomplete = true;
                thread(report, ref, recording.title(), "UNAVAILABLE",
                        "transcription locale indisponible sur ce poste : l'enregistrement sera repris");
                continue;
            }
            TranscriptionJob job = transcriber.start("radar-" + RadarExchanges.digest(ref).substring(0, 24), file,
                    recording.startedAt());
            if (!await(job, context, index, due.size())) {
                report.stopped = true;
                break;
            }
            if (job.phase() != TranscriptionJob.Phase.TERMINE) {
                failed++;
                report.incomplete = true;
                thread(report, ref, recording.title(), "FAILED",
                        job.failure().isBlank() ? "la transcription n'a pas abouti" : job.failure());
                continue;
            }
            List<RadarExchanges.Line> lines = new ArrayList<>();
            for (TeamsTranscriptCue cue : job.cues()) {
                if (cue.isReadable()) {
                    lines.add(new RadarExchanges.Line(ref + "/" + cue.at().toEpochMilli() + "/"
                            + Integer.toHexString(cue.text().hashCode()), cue.at(), cue.speakerId(),
                            cue.speakerDisplayName(), false, cue.text(), null));
                }
            }
            if (lines.isEmpty()) {
                failed++;
                report.incomplete = true;
                thread(report, ref, recording.title(), "FAILED", "aucune parole reconnue");
                continue;
            }
            lines.sort(Comparator.comparing(RadarExchanges.Line::occurredAt));
            boolean uploaded = true;
            for (RadarExchanges.Chunk chunk : RadarExchanges.chunks(mapper, "LOCAL_RECORDING", ref, recording.title(),
                    null, lines)) {
                ObjectNode body = mapper.createObjectNode();
                body.set("batch", chunk.batch());
                ObjectNode cursor = body.putArray("cursors").addObject();
                cursor.put("ref", ref);
                cursor.put("kind", "RECORDING");
                cursor.put("at", chunk.newest().toString());
                try {
                    JsonNode answer = context.submit(body);
                    if (context.stopped() || "STOPPED".equals(answer.path("status").asText(""))) {
                        report.stopped = true;
                        break;
                    }
                    report.batches++;
                    report.messages += chunk.messages();
                } catch (IOException e) {
                    uploaded = false;
                    break;
                }
            }
            if (report.stopped) {
                break;
            }
            if (uploaded) {
                transcribed++;
                if (recording.dateGuessed()) {
                    thread(report, ref, recording.title(), "DATE_GUESSED",
                            "transcrit ; date déduite du fichier (ajoutez un compagnon .json pour la préciser)");
                }
            } else {
                failed++;
                report.incomplete = true;
                thread(report, ref, recording.title(), "FAILED", "un lot n'a pas pu remonter : l'enregistrement sera repris");
            }
        }
        counts.put("found", found);
        counts.put("transcribed", transcribed);
        counts.put("skipped", skipped);
        counts.put("deferred", deferred);
        counts.put("failed", failed);
        counts.put("unavailable", unavailable);
        return report;
    }

    /** Attend la fin d'une transcription en battant ; faux si la synchro a été close. */
    private boolean await(TranscriptionJob job, RadarSyncContext context, int index, int total) {
        Instant deadline = clock.get().plus(MAX_TRANSCRIPTION);
        long sinceBeat = 0;
        while (!job.isOver()) {
            if (context.stopped()) {
                return false;
            }
            if (!clock.get().isBefore(deadline)) {
                job.failed("La transcription a dépassé " + MAX_TRANSCRIPTION.toHours() + " heures.", "");
                return true;
            }
            if (sleeper != null) {
                sleeper.sleep(POLL_MS);
            }
            sinceBeat += POLL_MS;
            if (sinceBeat >= 30_000L) {
                sinceBeat = 0;
                if (!context.progress("depot", index, total)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Les fichiers audio ou vidéo du premier niveau, non vides, stables, du plus ancien au plus récent. */
    List<Path> candidates() throws IOException {
        if (depot == null || !Files.isDirectory(depot)) {
            return List.of();
        }
        Instant stable = clock.get().minus(STABLE_AFTER);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> listing = Files.list(depot)) {
            for (Path path : listing.toList()) {
                String name = path.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (name.startsWith(".") || !Files.isRegularFile(path)
                        || EXTENSIONS.stream().noneMatch(lower::endsWith)) {
                    continue;
                }
                if (Files.size(path) == 0 || Files.getLastModifiedTime(path).toInstant().isAfter(stable)) {
                    continue; // vide, ou copie peut-être en cours : la synchro suivante le prendra
                }
                files.add(path);
            }
        }
        files.sort(Comparator.comparing(path -> {
            try {
                return Files.getLastModifiedTime(path).toInstant();
            } catch (IOException e) {
                return Instant.EPOCH;
            }
        }));
        return files;
    }

    /** La référence stable d'un enregistrement : nom, taille, date de modification. */
    static String referenceOf(Path file) {
        try {
            return "depot:" + RadarExchanges.digest(file.getFileName() + "|" + Files.size(file) + "|"
                    + Files.getLastModifiedTime(file).toMillis());
        } catch (IOException e) {
            return "depot:" + RadarExchanges.digest(String.valueOf(file.getFileName()));
        }
    }

    /** Le titre et la date d'un enregistrement. */
    record Recording(String title, Instant startedAt, boolean dateGuessed) {
    }

    /** Compagnon JSON, puis nom daté, puis le fichier lui-même (dit). */
    Recording describe(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String title = base.strip();
        Instant at = null;
        Path companion = file.resolveSibling(base + ".json");
        if (Files.isRegularFile(companion)) {
            try {
                JsonNode node = mapper.readTree(Files.readString(companion));
                String declared = node.path("title").asText("").strip();
                if (!declared.isEmpty()) {
                    title = declared.length() <= 200 ? declared : declared.substring(0, 200);
                }
                at = instantOf(node.path("date").asText(""));
            } catch (IOException | RuntimeException e) {
                // compagnon illisible : repli sur le nom, puis la date du fichier
            }
        }
        Matcher matcher = DATED_NAME.matcher(base.strip());
        if (matcher.matches()) {
            if (at == null) {
                at = instantOf(matcher.group(1) + "T" + String.format("%02d", Integer.parseInt(matcher.group(2))) + ":"
                        + matcher.group(3));
            }
            if (matcher.group(4) != null && title.equals(base.strip())) {
                title = matcher.group(4).strip();
            }
        }
        if (at != null) {
            return new Recording(title, at, false);
        }
        try {
            return new Recording(title, Files.getLastModifiedTime(file).toInstant(), true);
        } catch (IOException e) {
            return new Recording(title, clock.get(), true);
        }
    }

    private static Instant instantOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(value.strip()).toInstant();
            } catch (DateTimeParseException e2) {
                try {
                    return LocalDateTime.parse(value.strip()).atZone(ZoneId.systemDefault()).toInstant();
                } catch (DateTimeParseException e3) {
                    return null;
                }
            }
        }
    }

    private static Set<String> done(JsonNode input) {
        Set<String> refs = new HashSet<>();
        JsonNode array = input == null ? null : input.path("depot_done");
        if (array != null && array.isArray()) {
            array.forEach(node -> refs.add(node.asText("")));
        }
        return refs;
    }

    private void thread(Report report, String ref, String label, String status, String detail) {
        ObjectNode node = mapper.createObjectNode();
        node.put("ref", ref);
        String name = label == null ? "" : label.strip();
        node.put("label", name.length() <= TeamsRadarCollector.MAX_LABEL_CHARS ? name
                : name.substring(0, TeamsRadarCollector.MAX_LABEL_CHARS) + "…");
        node.put("kind", "RECORDING");
        node.put("status", status);
        node.put("detail", detail);
        report.threads.add(node);
    }

    /** Ajoute le relevé du dossier à la couverture de la collecte Teams, et rend l'issue d'ensemble. */
    static RadarCollector.Outcome merge(RadarCollector.Outcome teams, Report depot) {
        ObjectNode coverage = teams.coverage() == null ? new ObjectMapper().createObjectNode() : teams.coverage();
        coverage.set("depot", depot.depot);
        JsonNode existing = coverage.path("threads");
        ArrayNode threads = existing instanceof ArrayNode array ? array : coverage.putArray("threads");
        for (ObjectNode thread : depot.threads) {
            if (threads.size() < TeamsRadarCollector.MAX_LISTED_THREADS) {
                threads.add(thread);
            }
        }
        coverage.put("batches", coverage.path("batches").asInt(0) + depot.batches);
        coverage.put("messages", coverage.path("messages").asInt(0) + depot.messages);
        String status = teams.status();
        if ("SUCCEEDED".equals(status) && (depot.incomplete || depot.stopped)) {
            status = "PARTIAL";
        }
        return new RadarCollector.Outcome(status, coverage);
    }
}
