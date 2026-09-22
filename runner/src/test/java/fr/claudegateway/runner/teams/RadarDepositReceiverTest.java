package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fr.claudegateway.runner.ToolOutcome;

/** F-104 / SF-104-04 — recevoir un enregistrement déposé depuis l'écran, par morceaux. */
class RadarDepositReceiverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-13T20:00:00Z");

    @TempDir
    Path depot;

    private long usable = Long.MAX_VALUE;

    private RadarDepositReceiver receiver() {
        return new RadarDepositReceiver(depot, () -> NOW, dir -> usable);
    }

    private static ObjectNode op(String op, String id) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("op", op);
        node.put("upload_id", id);
        return node;
    }

    private static JsonNode json(ToolOutcome outcome) throws Exception {
        assertTrue(outcome.ok(), () -> "attendu ok : " + outcome.errorMessage());
        return MAPPER.readTree(outcome.content());
    }

    private static ObjectNode open(String id, String name, long size) {
        ObjectNode node = op("open", id);
        node.put("file_name", name);
        node.put("size", size);
        node.put("title", "Atelier sécurité");
        node.put("recorded_at", "2026-09-12T10:00:00+02:00");
        return node;
    }

    private static ObjectNode openWithSubject(String id, String name, long size, String subjectId) {
        ObjectNode node = open(id, name, size);
        node.put("subject_id", subjectId);
        return node;
    }

    private static ObjectNode chunk(String id, long offset, byte[] data) {
        ObjectNode node = op("chunk", id);
        node.put("offset", offset);
        node.put("data", Base64.getEncoder().encodeToString(data));
        return node;
    }

    @Test
    @DisplayName("nominal : ouvrir, trois morceaux, finir — fichier identique, compagnon SF-100-05, rien de caché ne reste")
    void nominal() throws Exception {
        RadarDepositReceiver receiver = receiver();
        String id = UUID.randomUUID().toString();
        byte[] content = new byte[1_200_000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        assertTrue(json(receiver.handle(open(id, "../../Réunion salle B.m4a", content.length))).path("accepted").asBoolean());
        int step = RadarDepositReceiver.MAX_CHUNK_BYTES;
        for (int offset = 0; offset < content.length; offset += step) {
            byte[] part = Arrays.copyOfRange(content, offset, Math.min(content.length, offset + step));
            JsonNode answer = json(receiver.handle(chunk(id, offset, part)));
            assertTrue(answer.path("accepted").asBoolean());
            assertEquals(Math.min(content.length, offset + step), answer.path("received").asLong());
        }
        JsonNode done = json(receiver.handle(op("finish", id)));

        assertEquals("Réunion salle B.m4a", done.path("file_name").asText());
        assertArrayEquals(content, Files.readAllBytes(depot.resolve("Réunion salle B.m4a")));
        JsonNode companion = MAPPER.readTree(Files.readString(depot.resolve("Réunion salle B.json")));
        assertEquals("Atelier sécurité", companion.path("title").asText());
        assertEquals("2026-09-12T10:00:00+02:00", companion.path("date").asText());
        try (var listing = Files.list(depot)) {
            assertTrue(listing.noneMatch(p -> p.getFileName().toString().startsWith(".")));
        }

        // Le collecteur de SF-100-05 le relève, avec le titre et la date du compagnon.
        Path deposited = depot.resolve("Réunion salle B.m4a");
        Files.setLastModifiedTime(deposited, FileTime.from(NOW.minusSeconds(600)));
        RadarDepositCollector collector = new RadarDepositCollector(depot, null, () -> NOW, millis -> { });
        assertEquals(1, collector.candidates().size());
        assertEquals("Atelier sécurité", collector.describe(deposited).title());
        assertEquals(Instant.parse("2026-09-12T08:00:00Z"), collector.describe(deposited).startedAt());
    }

    @Test
    @DisplayName("reprise : le même morceau renvoyé est accepté sans duplication ; un offset faux est refusé")
    void resumeAndOffsetMismatch() throws Exception {
        RadarDepositReceiver receiver = receiver();
        String id = UUID.randomUUID().toString();
        json(receiver.handle(open(id, "a.mp3", 10)));
        byte[] first = {1, 2, 3, 4, 5};
        json(receiver.handle(chunk(id, 0, first)));
        JsonNode again = json(receiver.handle(chunk(id, 0, first)));
        assertTrue(again.path("accepted").asBoolean());
        assertEquals(5, again.path("received").asLong());

        JsonNode wrong = json(receiver.handle(chunk(id, 7, new byte[] {9})));
        assertFalse(wrong.path("accepted").asBoolean());
        assertEquals("OFFSET_MISMATCH", wrong.path("reason").asText());

        JsonNode over = json(receiver.handle(chunk(id, 5, new byte[6])));
        assertEquals("SIZE_EXCEEDED", over.path("reason").asText());

        JsonNode incomplete = json(receiver.handle(op("finish", id)));
        assertEquals("INCOMPLETE", incomplete.path("reason").asText());
    }

    @Test
    @DisplayName("bornes : extension, taille, place disque, morceau trop gros, titre manquant, identifiant illisible")
    void bounds() throws Exception {
        RadarDepositReceiver receiver = receiver();
        String id = UUID.randomUUID().toString();
        assertFalse(receiver.handle(open(id, "rapport.pdf", 10)).ok());
        assertFalse(receiver.handle(open(id, "a.mp3", RadarDepositReceiver.MAX_BYTES + 1)).ok());
        ObjectNode untitled = open(id, "a.mp3", 10);
        untitled.put("title", " ");
        assertFalse(receiver.handle(untitled).ok());
        assertFalse(receiver.handle(op("open", "../../etc")).ok());

        usable = 1_000;
        assertEquals("DISK_FULL", json(receiver.handle(open(id, "a.mp3", 10))).path("reason").asText());
        usable = Long.MAX_VALUE;

        json(receiver.handle(open(id, "a.mp3", RadarDepositReceiver.MAX_BYTES)));
        assertFalse(receiver.handle(chunk(id, 0, new byte[RadarDepositReceiver.MAX_CHUNK_BYTES + 1])).ok());
    }

    @Test
    @DisplayName("nom nettoyé, collision « (2) », abandon, dépôt inconnu, purge des dépôts de plus de 24 h")
    void namesAbortUnknownAndPurge() throws Exception {
        RadarDepositReceiver receiver = receiver();
        assertEquals("a_b_.wav", RadarDepositReceiver.cleanName("C:\\\\x\\\\a:b?.wav"));
        assertEquals(".mp3".length() + 116, RadarDepositReceiver.cleanName("x".repeat(300) + ".mp3").length());

        for (int round = 0; round < 2; round++) {
            String id = UUID.randomUUID().toString();
            json(receiver.handle(open(id, "point.mp3", 2)));
            json(receiver.handle(chunk(id, 0, new byte[] {1, 2})));
            json(receiver.handle(op("finish", id)));
        }
        assertTrue(Files.exists(depot.resolve("point.mp3")));
        assertTrue(Files.exists(depot.resolve("point (2).mp3")));
        assertTrue(Files.exists(depot.resolve("point (2).json")));

        String aborted = UUID.randomUUID().toString();
        json(receiver.handle(open(aborted, "b.mp3", 2)));
        assertTrue(json(receiver.handle(op("abort", aborted))).path("aborted").asBoolean());
        assertEquals("UNKNOWN_UPLOAD", json(receiver.handle(chunk(aborted, 0, new byte[] {1}))).path("reason").asText());

        String stale = UUID.randomUUID().toString();
        json(receiver.handle(open(stale, "c.mp3", 2)));
        try (var listing = Files.list(depot)) {
            for (Path path : listing.filter(p -> p.getFileName().toString().startsWith(".upload-")).toList()) {
                Files.setLastModifiedTime(path, FileTime.from(NOW.minusSeconds(25 * 3600)));
            }
        }
        json(receiver.handle(open(UUID.randomUUID().toString(), "d.mp3", 2)));
        assertFalse(Files.exists(depot.resolve(".upload-" + stale + ".part")));
    }

    @Test
    @DisplayName("sans volet Teams : refus dit, sans dossier ; le dépôt ne prend pas le verrou de lecture")
    void disabledTeams() throws Exception {
        TeamsTools disabled = TeamsTools.disabled("--no-teams");
        JsonNode refused = json(disabled.execute(RadarTools.DEPOSIT, open(UUID.randomUUID().toString(), "a.mp3", 2), null));
        assertEquals("TEAMS_DISABLED", refused.path("reason").asText());
        assertFalse(new RadarDepositReceiver(null, () -> NOW, dir -> 0).handle(open(UUID.randomUUID().toString(), "a.mp3", 2))
                .content().contains("\"accepted\":true"));
    }

    // ------------------------------------- transcrire tout de suite (F-147 / SF-147-01)

    /** Un moteur de transcription en dur : on vérifie le BRANCHEMENT, pas la transcription. */
    private static final class Engine implements RadarDepositReceiver.Transcriber {
        Path seen;
        String id;
        final TranscriptionJob job = new TranscriptionJob("job-1");
        RuntimeException refuse;

        @Override
        public TranscriptionJob start(String id, Path file, Instant startedAt) {
            if (refuse != null) {
                throw refuse;
            }
            this.id = id;
            this.seen = file;
            return job;
        }
    }

    private RadarDepositReceiver receiverWith(Engine engine) {
        return new RadarDepositReceiver(depot, () -> NOW, dir -> usable, engine);
    }

    /** F-147 / SF-147-02 : ce qui porte le texte à la réunion — ici, un carnet. */
    private static final class Sink implements RadarDepositReceiver.TranscriptSink {
        String meetingId;
        TranscriptionJob job;
        RuntimeException refuse;

        @Override
        public boolean deposit(String meetingId, TranscriptionJob job) {
            if (refuse != null) {
                throw refuse;
            }
            this.meetingId = meetingId;
            this.job = job;
            return true;
        }
    }

    /** L'attente jouée ici même : le test n'a pas à dormir pour prouver la chaîne. */
    private RadarDepositReceiver receiverWith(Engine engine, Sink sink) {
        return new RadarDepositReceiver(depot, () -> NOW, dir -> usable, engine, sink, Runnable::run);
    }

    private void deposit(RadarDepositReceiver receiver, String id, byte[] data) throws Exception {
        json(receiver.handle(open(id, "reunion.mp4", data.length)));
        json(receiver.handle(chunk(id, 0, data)));
    }

    @Test
    @DisplayName("LE CRITÈRE : finir un dépôt DÉCLENCHE la transcription, sans attendre aucune synchro")
    void finishingStartsTheTranscription() throws Exception {
        Engine engine = new Engine();
        RadarDepositReceiver receiver = receiverWith(engine);
        byte[] data = "video".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        deposit(receiver, "11111111-1111-1111-1111-111111111111", data);

        JsonNode done = json(receiver.handle(op("finish", "11111111-1111-1111-1111-111111111111")));

        assertEquals("started", done.path("transcription").asText());
        assertEquals("job-1", done.path("job_id").asText());
        // Le moteur a bien reçu le fichier DÉPOSÉ, pas le fichier partiel.
        assertEquals("reunion.mp4", engine.seen.getFileName().toString());
        // Et la phase voyage en toutes lettres : c'est ce que l'écran affichera.
        assertTrue(done.path("phase_label").asText().length() > 5, "la phase doit être lisible");
    }

    @Test
    @DisplayName("où en est la transcription : la phase, en toutes lettres")
    void statusTellsThePhase() throws Exception {
        Engine engine = new Engine();
        RadarDepositReceiver receiver = receiverWith(engine);
        deposit(receiver, "22222222-2222-2222-2222-222222222222", "video".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        json(receiver.handle(op("finish", "22222222-2222-2222-2222-222222222222")));

        JsonNode status = json(receiver.handle(op("status", "22222222-2222-2222-2222-222222222222")));

        assertTrue(status.path("known").asBoolean(), "le travail doit être connu");
        assertEquals("job-1", status.path("job_id").asText());
        assertTrue(status.path("phase_label").asText().length() > 5);
    }

    @Test
    @DisplayName("un dépôt inconnu répond clairement, il n'invente pas un travail")
    void statusOfAnUnknownDepositSaysSo() throws Exception {
        JsonNode status = json(receiver().handle(op("status", "99999999-9999-9999-9999-999999999999")));

        assertFalse(status.path("known").asBoolean());
    }

    @Test
    @DisplayName("un moteur qui refuse ne fait PAS échouer le dépôt : le fichier est arrivé entier")
    void arefusingEngineNeverBreaksTheDeposit() throws Exception {
        Engine engine = new Engine();
        engine.refuse = new IllegalStateException("ffmpeg introuvable");
        RadarDepositReceiver receiver = receiverWith(engine);
        deposit(receiver, "33333333-3333-3333-3333-333333333333", "video".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        JsonNode done = json(receiver.handle(op("finish", "33333333-3333-3333-3333-333333333333")));

        assertTrue(done.path("deposited").asBoolean(), "le dépôt reste bon");
        assertEquals("refused", done.path("transcription").asText());
        assertTrue(done.path("transcription_error").asText().contains("ffmpeg"));
        assertTrue(java.nio.file.Files.exists(depot.resolve("reunion.mp4")), "le fichier reste en place");
    }

    @Test
    @DisplayName("sans moteur, le dépôt se comporte comme avant — et le dit")
    void withoutAnEngineTheDepositIsUnchanged() throws Exception {
        RadarDepositReceiver receiver = receiver();
        deposit(receiver, "44444444-4444-4444-4444-444444444444", "video".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        JsonNode done = json(receiver.handle(op("finish", "44444444-4444-4444-4444-444444444444")));

        assertTrue(done.path("deposited").asBoolean());
        assertEquals("unavailable", done.path("transcription").asText());
    }
    // ---------------------------------------------------------------- F-147 / SF-147-02 : la réunion du dépôt

    @Test
    @DisplayName("le sujet choisi au geste voyage avec le dépôt et revient à la fin — le poste ne le juge pas")
    void theSubjectTravelsWithTheDeposit() throws Exception {
        Engine engine = new Engine();
        RadarDepositReceiver receiver = receiverWith(engine);
        String id = "55555555-5555-5555-5555-555555555555";
        byte[] data = "video".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        json(receiver.handle(openWithSubject(id, "reunion.mp4", data.length,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")));
        json(receiver.handle(chunk(id, 0, data)));

        JsonNode done = json(receiver.handle(op("finish", id)));

        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", done.path("subject_id").asText());
    }

    @Test
    @DisplayName("LE CRITÈRE : la réunion annoncée reçoit le texte au terme du travail")
    void theMeetingReceivesTheText() throws Exception {
        Engine engine = new Engine();
        Sink sink = new Sink();
        RadarDepositReceiver receiver = receiverWith(engine, sink);
        String id = "66666666-6666-6666-6666-666666666666";
        deposit(receiver, id, "video".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        json(receiver.handle(op("finish", id)));
        engine.job.phase(TranscriptionJob.Phase.TERMINE);

        ObjectNode attach = op("attach", id);
        attach.put("meeting_id", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        JsonNode attached = json(receiver.handle(attach));

        assertTrue(attached.path("attached").asBoolean(), attached.toString());
        assertEquals("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", sink.meetingId);
        assertEquals(engine.job, sink.job, "c'est bien le travail de CE dépôt qui remonte");
    }

    @Test
    @DisplayName("sans moyen de faire remonter le texte, le dépôt le dit — il ne fait pas semblant")
    void withoutAnUplinkTheDepositSaysSo() throws Exception {
        Engine engine = new Engine();
        RadarDepositReceiver receiver = receiverWith(engine);
        String id = "77777777-7777-7777-7777-777777777777";
        deposit(receiver, id, "video".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        json(receiver.handle(op("finish", id)));

        ObjectNode attach = op("attach", id);
        attach.put("meeting_id", "cccccccc-cccc-cccc-cccc-cccccccccccc");
        JsonNode answer = json(receiver.handle(attach));

        assertFalse(answer.path("attached").asBoolean());
        assertEquals("NO_UPLINK", answer.path("reason").asText());
    }

    @Test
    @DisplayName("une remontée qui échoue ne casse rien : le texte reste sur la machine")
    void afailingUplinkKeepsTheTextHere() throws Exception {
        Engine engine = new Engine();
        Sink sink = new Sink();
        sink.refuse = new IllegalStateException("gateway injoignable");
        RadarDepositReceiver receiver = receiverWith(engine, sink);
        String id = "88888888-8888-8888-8888-888888888888";
        deposit(receiver, id, "video".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        json(receiver.handle(op("finish", id)));
        engine.job.phase(TranscriptionJob.Phase.TERMINE);

        ObjectNode attach = op("attach", id);
        attach.put("meeting_id", "dddddddd-dddd-dddd-dddd-dddddddddddd");
        JsonNode attached = json(receiver.handle(attach));

        assertTrue(attached.path("attached").asBoolean(), "l'adresse est prise ; c'est la remontée qui a échoué");
        assertTrue(java.nio.file.Files.exists(depot.resolve("reunion.mp4")), "le fichier reste en place");
    }

    @Test
    @DisplayName("une réunion annoncée sans identifiant est refusée")
    void anAttachWithoutMeetingIsRefused() throws Exception {
        ToolOutcome outcome = receiver().handle(op("attach", "99999999-9999-9999-9999-999999999999"));

        assertFalse(outcome.ok());
    }
}
