package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Rattraper un texte resté sur le poste</b> (F-147 / SF-147-06).
 *
 * <p>Un dépôt venu de l'écran peut perdre son texte en route : la gateway était injoignable, le runner
 * a redémarré pendant la transcription, ou le moteur manquait à ce moment-là. Le fichier, lui, est
 * toujours là, avec son compagnon — et la réunion, elle, attend.</p>
 *
 * <p><b>Ce n'est pas la boîte aux lettres.</b> On ne relève pas le dossier : on ne reprend que les
 * dépôts <b>venus de l'écran</b>, reconnaissables à l'{@code meeting_id} que la gateway a inscrit dans
 * leur compagnon. Un fichier posé à la main n'en a pas, et n'est donc <b>jamais</b> pris. Et cela se
 * passe <b>au démarrage</b>, un moment où l'on sait que le poste est vivant — pas à une heure que
 * personne ne peut prévoir.</p>
 */
final class RadarDepositResume {

    /** Combien de dépôts sont repris par démarrage : la reprise ne doit pas retarder un poste. */
    static final int MAX_RESUMED = 3;
    /** Attente maximale d'une transcription reprise. */
    static final Duration MAX_WAIT = Duration.ofHours(6);
    static final long POLL_MS = 1_000L;

    private final Path depot;
    private final RadarDepositReceiver.Transcriber transcriber;
    private final RadarDepositReceiver.TranscriptSink sink;
    private final Consumer<String> say;

    RadarDepositResume(Path depot, RadarDepositReceiver.Transcriber transcriber,
            RadarDepositReceiver.TranscriptSink sink, Consumer<String> say) {
        this.depot = depot;
        this.transcriber = transcriber;
        this.sink = sink;
        this.say = say == null ? line -> { } : say;
    }

    /** Vrai si tout est là pour reprendre : sans moteur ni remontée, il n'y a rien à tenter. */
    boolean ready() {
        return depot != null && transcriber != null && sink != null && Files.isDirectory(depot);
    }

    /**
     * Les dépôts à reprendre : ceux dont le compagnon porte une réunion et <b>pas</b> la marque de
     * remontée. Les plus anciens d'abord — ils attendent depuis le plus longtemps.
     */
    List<Pending> pending() {
        if (!ready()) {
            return List.of();
        }
        List<Pending> found = new ArrayList<>();
        try (Stream<Path> entries = Files.list(depot)) {
            entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(companion -> {
                        JsonNode node = RadarDepositCompanion.read(companion);
                        String meetingId = node.path("meeting_id").asText("");
                        if (meetingId.isBlank() || node.path("transcript_sent").asBoolean(false)) {
                            return;
                        }
                        Path recording = RadarDepositCompanion.recordingOf(companion);
                        if (recording == null) {
                            return;
                        }
                        found.add(new Pending(recording, meetingId, startedAt(node, recording)));
                    });
        } catch (IOException e) {
            return List.of();
        }
        found.sort(Comparator.comparing(Pending::startedAt));
        return found.size() > MAX_RESUMED ? found.subList(0, MAX_RESUMED) : found;
    }

    /** Reprend ce qui attend : transcrit si besoin, puis fait remonter le texte. */
    void resumeAll() {
        for (Pending pending : pending()) {
            try {
                resume(pending);
            } catch (RuntimeException e) {
                // Rien n'est marqué : le prochain démarrage réessaiera. Un poste qui démarre ne doit
                // pas s'arrêter là-dessus.
                say.accept("Radar : la reprise de « " + pending.recording().getFileName()
                        + " » n'a pas abouti ; elle sera retentée au prochain démarrage.");
            }
        }
    }

    void resume(Pending pending) {
        String id = "reprise-" + Integer.toHexString(pending.recording().toString().hashCode());
        TranscriptionJob job = transcriber.start(id, pending.recording(), pending.startedAt());
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        while (!job.isOver() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (sink.deposit(pending.meetingId(), job)) {
            RadarDepositCompanion.markSent(pending.recording());
            say.accept("Radar : le texte de « " + pending.recording().getFileName()
                    + " » a rejoint sa réunion.");
        }
    }

    private static Instant startedAt(JsonNode node, Path recording) {
        String date = node.path("date").asText("");
        if (!date.isBlank()) {
            try {
                return java.time.OffsetDateTime.parse(date).toInstant();
            } catch (RuntimeException ignored) {
                // Compagnon écrit à la main : on retombe sur la date du fichier.
            }
        }
        try {
            return Files.getLastModifiedTime(recording).toInstant();
        } catch (IOException e) {
            return Instant.now();
        }
    }

    /** Un dépôt qui attend son texte. */
    record Pending(Path recording, String meetingId, Instant startedAt) {
    }
}
