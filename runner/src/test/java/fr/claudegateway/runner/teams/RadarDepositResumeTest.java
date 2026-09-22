package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F-147 / SF-147-06 — rattraper un texte resté sur le poste.
 *
 * <p>Ce que ces tests protègent avant tout : <b>la boîte aux lettres ne revient pas</b>. Seul un dépôt
 * venu de l'écran — celui dont le compagnon porte une réunion — est repris.</p>
 */
class RadarDepositResumeTest {

    @TempDir
    Path depot;

    /** Un moteur de papier : le travail est déjà terminé, avec une réplique. */
    private static final class Engine implements RadarDepositReceiver.Transcriber {
        final List<Path> seen = new ArrayList<>();

        @Override
        public TranscriptionJob start(String id, Path file, Instant startedAt) {
            seen.add(file);
            TranscriptionJob job = new TranscriptionJob(id);
            job.cues(List.of(new TeamsTranscriptCue(Instant.parse("2026-09-12T08:00:00Z"), 1000,
                    "u1", "Paul", "On part sur Okta.")));
            return job.phase(TranscriptionJob.Phase.TERMINE);
        }
    }

    /** Le carnet de ce qui est remonté. */
    private static final class Sink implements RadarDepositReceiver.TranscriptSink {
        final List<String> meetings = new ArrayList<>();
        boolean refuse;

        @Override
        public boolean deposit(String meetingId, TranscriptionJob job) {
            if (refuse) {
                return false;
            }
            meetings.add(meetingId);
            return true;
        }
    }

    private Path deposit(String base, String companionJson) throws Exception {
        Path recording = depot.resolve(base + ".mp4");
        Files.writeString(recording, "video");
        Files.writeString(depot.resolve(base + ".json"), companionJson);
        return recording;
    }

    @Test
    @DisplayName("LE CRITÈRE : un dépôt porteur d'une réunion et SANS marque est repris, puis marqué")
    void apendingDepositIsResumedAndMarked() throws Exception {
        Path recording = deposit("reunion",
                "{\"title\":\"Atelier\",\"date\":\"2026-09-12T10:00:00+02:00\","
                        + "\"meeting_id\":\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\"}");
        Engine engine = new Engine();
        Sink sink = new Sink();

        new RadarDepositResume(depot, engine, sink, null).resumeAll();

        assertEquals(List.of(recording), engine.seen);
        assertEquals(List.of("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), sink.meetings);
        assertTrue(RadarDepositCompanion.read(RadarDepositCompanion.of(recording))
                .path("transcript_sent").asBoolean(), "le compagnon doit porter la marque");
    }

    @Test
    @DisplayName("un dépôt DÉJÀ remonté n'est pas repris : la réunion ne reçoit pas deux fois le même texte")
    void analreadySentDepositIsLeftAlone() throws Exception {
        deposit("deja", "{\"meeting_id\":\"cccccccc-cccc-cccc-cccc-cccccccccccc\",\"transcript_sent\":true}");
        Engine engine = new Engine();
        Sink sink = new Sink();

        new RadarDepositResume(depot, engine, sink, null).resumeAll();

        assertTrue(engine.seen.isEmpty(), "rien ne doit être retranscrit");
        assertTrue(sink.meetings.isEmpty());
    }

    @Test
    @DisplayName("LA BOÎTE AUX LETTRES NE REVIENT PAS : un fichier posé à la main n'est JAMAIS pris")
    void afileDroppedByHandIsNeverTaken() throws Exception {
        deposit("a-la-main", "{\"title\":\"Posé à la main\",\"date\":\"2026-09-12T10:00:00+02:00\"}");
        Files.writeString(depot.resolve("sans-compagnon.mp4"), "video");
        Engine engine = new Engine();
        Sink sink = new Sink();

        new RadarDepositResume(depot, engine, sink, null).resumeAll();

        assertTrue(engine.seen.isEmpty(), "aucun fichier sans réunion ne doit être transcrit");
        assertTrue(sink.meetings.isEmpty());
    }

    @Test
    @DisplayName("une remontée refusée ne marque rien : le prochain démarrage réessaiera")
    void arefusedUploadIsNotMarked() throws Exception {
        Path recording = deposit("refuse",
                "{\"meeting_id\":\"dddddddd-dddd-dddd-dddd-dddddddddddd\"}");
        Sink sink = new Sink();
        sink.refuse = true;

        new RadarDepositResume(depot, new Engine(), sink, null).resumeAll();

        assertFalse(RadarDepositCompanion.read(RadarDepositCompanion.of(recording))
                .path("transcript_sent").asBoolean(), "rien ne doit être marqué");
        assertTrue(Files.exists(recording), "le fichier reste en place");
    }

    @Test
    @DisplayName("la reprise est BORNÉE : au-delà du plafond, les autres attendront le prochain démarrage")
    void theresumeIsBounded() throws Exception {
        for (int i = 0; i < RadarDepositResume.MAX_RESUMED + 2; i++) {
            deposit("depot-" + i, "{\"meeting_id\":\"eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee" + i + "\"}");
        }

        List<RadarDepositResume.Pending> pending =
                new RadarDepositResume(depot, new Engine(), new Sink(), null).pending();

        assertEquals(RadarDepositResume.MAX_RESUMED, pending.size());
    }

    @Test
    @DisplayName("sans moteur ni remontée, il n'y a rien à tenter — et on ne fait pas semblant")
    void withoutEngineOrUplinkNothingIsAttempted() {
        assertFalse(new RadarDepositResume(depot, null, new Sink(), null).ready());
        assertFalse(new RadarDepositResume(depot, new Engine(), null, null).ready());
        assertTrue(new RadarDepositResume(depot, new Engine(), new Sink(), null).ready());
    }
}
