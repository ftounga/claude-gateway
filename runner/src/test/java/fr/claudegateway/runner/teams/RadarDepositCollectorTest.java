package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F-100 / SF-100-05 — <b>le dossier de dépôt</b> : les enregistrements hors Teams, transcrits sur la machine,
 * dont seul le texte remonte, une seule fois chacun.
 */
class RadarDepositCollectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-13T20:00:00Z");

    @TempDir
    Path depot;

    /** Un moteur de transcription de papier : rend des répliques datées depuis le début donné. */
    static final class PaperTranscriber implements RadarDepositCollector.Transcriber {
        final List<String> started = new ArrayList<>();
        final Map<String, String> failures = new ConcurrentHashMap<>();
        boolean silent;

        @Override
        public TranscriptionJob start(String id, Path file, Instant startedAt) {
            started.add(file.getFileName().toString());
            TranscriptionJob job = new TranscriptionJob(id);
            String failure = failures.get(file.getFileName().toString());
            if (failure != null) {
                return job.failed(failure, "");
            }
            if (!silent) {
                job.cues(List.of(new TeamsTranscriptCue(startedAt.plusSeconds(5), 3000, "", "", "On valide le budget IAM."),
                        new TeamsTranscriptCue(startedAt.plusSeconds(12), 2500, "", "", "Marc envoie le devis jeudi.")));
            }
            return job.phase(TranscriptionJob.Phase.TERMINE);
        }
    }

    private Path deposit(String name, byte[] content, Instant modified) throws Exception {
        Path file = depot.resolve(name);
        Files.write(file, content);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    private static RadarAssignment assignment(String... done) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("sync_id", "7f000001-0000-4000-8000-0000000000bb");
        input.put("window_from", "2026-09-01T00:00:00Z");
        var array = input.putArray("depot_done");
        for (String ref : done) {
            array.add(ref);
        }
        return RadarAssignment.from(input);
    }

    private RadarDepositCollector collector(PaperTranscriber transcriber) {
        return new RadarDepositCollector(depot, transcriber, () -> NOW, millis -> { });
    }

    @Test
    @DisplayName("Relevé : audio/vidéo stables et non vides du premier niveau ; le reste est ignoré sans bruit")
    void candidates() throws Exception {
        Instant old = NOW.minusSeconds(3600);
        deposit("reunion.m4a", new byte[] { 1, 2, 3 }, old);
        deposit("VISIO.MP4", new byte[] { 1 }, old);
        deposit("notes.txt", new byte[] { 1 }, old);
        deposit("vide.mp3", new byte[0], old);
        deposit("en-cours.wav", new byte[] { 1 }, NOW.minusSeconds(30));
        deposit(".cache.mp3", new byte[] { 1 }, old);
        Files.createDirectories(depot.resolve("archives"));
        deposit("archives/ancien.mp3", new byte[] { 1 }, old);

        List<Path> found = collector(new PaperTranscriber()).candidates();

        assertEquals(List.of("VISIO.MP4", "reunion.m4a"), found.stream().map(p -> p.getFileName().toString()).sorted().toList());
    }

    @Test
    @DisplayName("Transcrit sur la machine, seul le texte remonte en LOCAL_RECORDING ; le fichier reste intact ; déjà fait : sauté")
    void transcribesOnceAndUploadsText() throws Exception {
        byte[] content = { 9, 8, 7, 6 };
        Path file = deposit("2026-09-12 14h30 - Comité budget.m4a", content, NOW.minusSeconds(7200));
        PaperTranscriber transcriber = new PaperTranscriber();
        TeamsRadarCollectorTest.PaperContext context = new TeamsRadarCollectorTest.PaperContext();

        RadarDepositCollector.Report report = collector(transcriber).collect(assignment(), context);

        assertEquals(1, context.submitted.size());
        ObjectNode body = context.submitted.get(0);
        JsonNode exchange = body.path("batch").path("exchanges").get(0);
        String ref = RadarDepositCollector.referenceOf(file);
        assertEquals("LOCAL_RECORDING", exchange.path("source").asText());
        assertEquals(ref, exchange.path("conversationRef").asText());
        assertEquals("Comité budget", exchange.path("title").asText());
        Instant start = LocalDateTime.parse("2026-09-12T14:30").atZone(ZoneId.systemDefault()).toInstant();
        assertEquals(start.plusSeconds(5).toString(), exchange.path("messages").get(0).path("occurredAt").asText());
        assertEquals("On valide le budget IAM.", exchange.path("messages").get(0).path("text").asText());
        assertTrue(body.path("batch").path("batchKey").asText().startsWith("depot:"));
        assertEquals("RECORDING", body.path("cursors").get(0).path("kind").asText());
        assertEquals(ref, body.path("cursors").get(0).path("ref").asText());
        assertFalse(body.toString().contains(depot.toString()), "aucun chemin de la machine ne remonte");
        assertArrayEquals(content, Files.readAllBytes(file), "le fichier déposé reste intact");
        assertEquals(1, report.depot.path("transcribed").asInt());

        TeamsRadarCollectorTest.PaperContext again = new TeamsRadarCollectorTest.PaperContext();
        RadarDepositCollector.Report second = collector(transcriber).collect(assignment(ref), again);
        assertTrue(again.submitted.isEmpty());
        assertEquals(1, second.depot.path("skipped").asInt());
        assertEquals(1, transcriber.started.size(), "un enregistrement n'est transcrit qu'une fois");
    }

    @Test
    @DisplayName("Titre et date : le compagnon JSON fait foi ; sinon la date du fichier, et c'est dit")
    void titleAndDate() throws Exception {
        Instant modified = NOW.minusSeconds(86_400);
        Path withCompanion = deposit("rec001.mp3", new byte[] { 1 }, modified);
        Files.writeString(depot.resolve("rec001.json"), "{\"title\":\"Point sécurité\",\"date\":\"2026-09-10T09:00:00Z\"}");
        Path plain = deposit("memo vocal.ogg", new byte[] { 1 }, modified);

        RadarDepositCollector collector = collector(new PaperTranscriber());
        RadarDepositCollector.Recording declared = collector.describe(withCompanion);
        assertEquals("Point sécurité", declared.title());
        assertEquals(Instant.parse("2026-09-10T09:00:00Z"), declared.startedAt());
        assertFalse(declared.dateGuessed());

        RadarDepositCollector.Recording guessed = collector.describe(plain);
        assertEquals("memo vocal", guessed.title());
        assertEquals(modified, guessed.startedAt());
        assertTrue(guessed.dateGuessed());

        Files.writeString(depot.resolve("rec001.json"), "{illisible");
        assertEquals("rec001", collector.describe(withCompanion).title());
    }

    @Test
    @DisplayName("Moteur absent : UNAVAILABLE ; échec ou aucune parole : FAILED ; rien ne remonte, l'issue devient partielle")
    void failuresAreNamed() throws Exception {
        Instant old = NOW.minusSeconds(3600);
        deposit("a.mp3", new byte[] { 1 }, old.minusSeconds(3));
        deposit("b.mp3", new byte[] { 2 }, old.minusSeconds(2));

        TeamsRadarCollectorTest.PaperContext context = new TeamsRadarCollectorTest.PaperContext();
        RadarDepositCollector.Report unavailable = new RadarDepositCollector(depot, null, () -> NOW, millis -> { })
                .collect(assignment(), context);
        assertEquals(2, unavailable.depot.path("unavailable").asInt());
        assertTrue(context.submitted.isEmpty());
        assertEquals("UNAVAILABLE", unavailable.threads.get(0).path("status").asText());

        PaperTranscriber transcriber = new PaperTranscriber();
        transcriber.failures.put("a.mp3", "Modèle de transcription introuvable.");
        transcriber.silent = true;
        RadarDepositCollector.Report failed = collector(transcriber).collect(assignment(), context);
        assertEquals(2, failed.depot.path("failed").asInt());
        assertEquals("Modèle de transcription introuvable.", failed.threads.get(0).path("detail").asText());
        assertEquals("aucune parole reconnue", failed.threads.get(1).path("detail").asText());
        assertTrue(context.submitted.isEmpty());

        RadarCollector.Outcome merged = RadarDepositCollector.merge(
                new RadarCollector.Outcome("SUCCEEDED", MAPPER.createObjectNode()), failed);
        assertEquals("PARTIAL", merged.status());
        assertEquals(2, merged.coverage().path("depot").path("failed").asInt());
    }

    @Test
    @DisplayName("Trois enregistrements par synchro, le reste reporté ; synchro close : arrêt")
    void capAndStop() throws Exception {
        Instant old = NOW.minusSeconds(3600);
        for (int index = 0; index < 5; index++) {
            deposit("rec" + index + ".mp3", new byte[] { (byte) index }, old.plusSeconds(index));
        }
        PaperTranscriber transcriber = new PaperTranscriber();
        TeamsRadarCollectorTest.PaperContext context = new TeamsRadarCollectorTest.PaperContext();
        RadarDepositCollector.Report report = collector(transcriber).collect(assignment(), context);
        assertEquals(3, report.depot.path("transcribed").asInt());
        assertEquals(2, report.depot.path("deferred").asInt());
        assertTrue(report.incomplete);

        TeamsRadarCollectorTest.PaperContext closed = new TeamsRadarCollectorTest.PaperContext();
        closed.answer = body -> MAPPER.createObjectNode().put("status", "STOPPED");
        RadarDepositCollector.Report stopped = collector(new PaperTranscriber()).collect(assignment(), closed);
        assertTrue(stopped.stopped);
        assertEquals(1, closed.submitted.size(), "arrêt dès la synchro close");
    }

    @Test
    @DisplayName("Chaîne : Teams puis dépôt ; dossier absent : la collecte Teams seule")
    void chain() throws Exception {
        deposit("rec.mp3", new byte[] { 1 }, NOW.minusSeconds(3600));
        RadarCollector teams = (assignment, context) -> new RadarCollector.Outcome("SUCCEEDED",
                MAPPER.createObjectNode().put("batches", 2));
        TeamsRadarCollectorTest.PaperContext context = new TeamsRadarCollectorTest.PaperContext();

        RadarCollector.Outcome outcome = RadarCollectors.chain(teams, collector(new PaperTranscriber()))
                .collect(assignment(), context);
        assertEquals("SUCCEEDED", outcome.status());
        assertEquals(3, outcome.coverage().path("batches").asInt());
        assertEquals(1, outcome.coverage().path("depot").path("transcribed").asInt());

        RadarCollector.Outcome teamsOnly = RadarCollectors.chain(teams, null).collect(assignment(), context);
        assertFalse(teamsOnly.coverage().has("depot"));
    }
}
