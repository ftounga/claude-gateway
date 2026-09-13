package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>Le travail long de la transcription</b> (F-91 / SF-91-03).
 *
 * <p>L'exécuteur est <b>direct</b> dans ces tests (chaque tâche s'exécute sur le fil appelant) : ce
 * qu'on éprouve est l'<b>enchaînement</b> et ce qui est <b>dit</b>, pas l'ordonnancement de threads.
 * Le caractère asynchrone, lui, se voit au montage : {@code ToolStack} donne un exécuteur mono-fil
 * démon, comme pour les moments.</p>
 */
@DisplayName("F-91 / SF-91-03 — le travail long de la transcription")
class TranscriptionWorkerTest {

    private static final Instant STARTED = Instant.parse("2026-09-13T14:32:00Z");

    @TempDir
    Path host;

    private TeamsWorkFolder folder;
    private CaptureStore store;
    private FakeProcesses processes;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        folder = new TeamsWorkFolder(host);
        store = new CaptureStore(folder);
        processes = new FakeProcesses();
        said.clear();
        answerLikeRealBinaries();
    }

    @Test
    @DisplayName("nominal : les répliques sont là, et la capture garde le chemin du fichier lisible")
    void nominal() throws IOException {
        CaptureRecord record = capture(true);
        writeCsv(record, "start,end,text\n0,2400,\"Bonjour à tous.\"\n");

        TranscriptionJob job = worker().startOrResume(record, said::add);

        assertEquals(TranscriptionJob.Phase.TERMINE, job.phase());
        assertEquals(1, job.cues().size());
        assertEquals(STARTED, job.cues().get(0).at());
        assertTrue(job.file().endsWith(LocalTranscription.READABLE), job.file());
        assertTrue(record.transcript().endsWith(LocalTranscription.READABLE));
        assertTrue(String.join(" ", said).contains("sans rien envoyer nulle part"));
    }

    @Test
    @DisplayName("une capture SANS SON : aucune transcription, ET LE MANQUE EST NOMMÉ")
    void aMutedCaptureIsNamed() throws IOException {
        CaptureRecord record = capture(false);

        TranscriptionJob job = worker().startOrResume(record, said::add);

        assertEquals(TranscriptionJob.Phase.ECHOUE, job.phase());
        assertTrue(job.failure().contains("sans le son"), job.failure());
        assertTrue(job.gaps().stream()
                .anyMatch(gap -> gap.detail().contains("aucune parole n'a pu être transcrite")),
                job.gaps().toString());
        // Et surtout : aucun modèle n'a été cherché, aucun processus lancé.
        assertTrue(processes.calls.isEmpty(), processes.calls.toString());
    }

    @Test
    @DisplayName("redemander la même capture NE RECOMMENCE PAS, et le dit")
    void resumingDoesNotRestart() throws IOException {
        CaptureRecord record = capture(true);
        writeCsv(record, "0,1000,\"Bonjour.\"\n");
        TranscriptionWorker worker = worker();
        worker.startOrResume(record, said::add);
        int callsAfterFirst = processes.calls.size();
        said.clear();

        TranscriptionJob again = worker.startOrResume(record, said::add);

        assertEquals(TranscriptionJob.Phase.TERMINE, again.phase());
        assertEquals(callsAfterFirst, processes.calls.size(), "rien ne doit avoir été relancé");
        assertTrue(String.join(" ", said).contains("plutôt que de tout recommencer"));
    }

    @Test
    @DisplayName("chaque étape est DITE pendant qu'elle dure : la conversation ne se fige pas")
    void everyStepIsSaid() throws IOException {
        CaptureRecord record = capture(true);
        writeCsv(record, "0,1000,\"Bonjour.\"\n");

        worker().startOrResume(record, said::add);

        String everything = String.join(" | ", said);
        assertTrue(everything.contains(TranscriptionJob.Phase.AUDIO.label()), everything);
        assertTrue(everything.contains(TranscriptionJob.Phase.MODELE.label()), everything);
        assertTrue(everything.contains(TranscriptionJob.Phase.TRANSCRIPTION.label()), everything);
    }

    @Test
    @DisplayName("le moteur en échec : le travail échoue AVEC son remède, la vidéo reste intacte")
    void anEngineFailureKeepsTheVideo() throws IOException {
        CaptureRecord record = capture(true);
        processes.answering(command -> {
            if (command.contains("-ocsv")) {
                return new ProcessRunner.ProcessResult(1, List.of(),
                        List.of("error: bad model"), false);
            }
            if (command.contains("-vn")) {
                try {
                    Files.writeString(Path.of(command.get(command.size() - 1)), "son de papier");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return FakeProcesses.version(Path.of(command.get(0)).getFileName().toString());
        });

        TranscriptionJob job = worker().startOrResume(record, said::add);

        assertEquals(TranscriptionJob.Phase.ECHOUE, job.phase());
        assertTrue(job.remedy().contains("bad model"), job.remedy());
        assertTrue(Files.isRegularFile(Path.of(record.video())));
        assertFalse(job.describe().isBlank());
    }

    // ------------------------------------------------------------------ montages

    @Test
    @DisplayName("F-108 / SF-108-05 : un FICHIER (enregistrement téléchargé) se transcrit sans être une capture")
    void aDownloadedRecordingIsTranscribedWithoutBecomingACapture() throws IOException {
        Path video = Files.createDirectories(folder.downloadsDir().resolve("rec-x"))
                .resolve("reunion.mp4");
        Files.writeString(video, "vidéo de papier");
        Path into = folder.workDir("transcription-rec-x");
        Files.writeString(into.resolve("transcript.csv"), "start,end,text\n0,1500,\"On commence.\"\n",
                StandardCharsets.UTF_8);

        TranscriptionJob job = worker().startOrResumeFile("rec-x", video, into, STARTED,
                "Transcription locale de l'enregistrement Teams", said::add);

        assertEquals(TranscriptionJob.Phase.TERMINE, job.phase(), job.failure());
        assertEquals(1, job.cues().size());
        assertEquals(STARTED, job.cues().get(0).at());
        assertTrue(job.file().startsWith(into.toString()), job.file());
        assertTrue(store.all().isEmpty(), "ce n'est pas une capture : le magasin n'est pas touché");
        assertFalse(Files.list(video.getParent()).anyMatch(path -> !path.equals(video)),
                "rien n'est écrit dans le dossier du téléchargement");
    }

    private TranscriptionWorker worker() {
        LocalToolchain toolchain = toolchain();
        return new TranscriptionWorker(store, new AudioTrack(toolchain, processes),
                new LocalTranscription(toolchain, processes),
                // Exécuteur direct : on éprouve l'enchaînement, pas l'ordonnancement.
                directPool());
    }

    /**
     * Un exécuteur qui exécute <b>sur le fil appelant</b>. Ce qui est éprouvé ici est
     * l'enchaînement et ce qui est dit ; l'asynchronisme réel appartient au montage.
     */
    private static java.util.concurrent.ExecutorService directPool() {
        return new java.util.concurrent.AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }

            @Override
            public void shutdown() {
                // Rien à arrêter.
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
                return true;
            }
        };
    }

    /** Une capture terminée, avec sa vidéo de papier sur le disque. */
    private CaptureRecord capture(boolean audio) throws IOException {
        Path dir = Files.createDirectories(folder.capturesDir().resolve("a1b2"));
        Path video = dir.resolve("capture.mp4");
        Files.writeString(video, "vidéo de papier");
        CaptureRecord record = new CaptureRecord("a1b2",
                new CaptureConsent(CapturePurpose.MEETING_WITH_OTHERS, true), video.toString(),
                new Watermark("francky", STARTED, CapturePurpose.MEETING_WITH_OTHERS))
                .startedAt(STARTED)
                .devices(new CaptureDevices(":0.0", audio ? "default" : ""))
                .finished(STARTED.plusSeconds(600), 4_096L);
        store.save(record);
        return record;
    }

    /** Ce que le moteur « aurait écrit ». Posé d'avance : aucun binaire ne tourne dans un test. */
    private void writeCsv(CaptureRecord record, String csv) throws IOException {
        Path dir = Path.of(record.video()).getParent();
        Files.writeString(dir.resolve("transcript.csv"), csv, StandardCharsets.UTF_8);
    }

    /**
     * L'outillage de papier : {@code ffmpeg} et le moteur sont déjà là, le modèle aussi. Aucun
     * téléchargement ne doit avoir lieu dans un test — le rapatriement a son propre test.
     */
    private LocalToolchain toolchain() {
        Path ffmpeg = writeBinary("ffmpeg");
        Path engine = writeBinary("whisper-cli");
        Path model = folder.toolsDir().resolve("modele-de-transcription").resolve("ggml-base.bin");
        try {
            Files.createDirectories(model.getParent());
            Files.writeString(model, "modèle de papier");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new LocalToolchain(folder, OperatingSystem.LINUX, processes, (url, into) -> {
            throw new IOException("aucun téléchargement ne doit avoir lieu dans ce test");
        }, said::add, name -> "ffmpeg".equals(name) ? ffmpeg
                : ("whisper-cli".equals(name) ? engine : null));
    }

    /**
     * Le comportement par défaut de l'outillage de papier : chaque binaire <b>s'identifie</b> sous
     * son propre nom (sans quoi {@link LocalToolchain} refuserait de s'en servir, à raison), et
     * {@code ffmpeg} « écrit » l'audio qu'on lui demande.
     */
    private void answerLikeRealBinaries() {
        processes.answering(command -> {
            String binary = Path.of(command.get(0)).getFileName().toString();
            if (command.contains("-vn")) {
                try {
                    Files.writeString(Path.of(command.get(command.size() - 1)), "son de papier");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return FakeProcesses.version(binary);
        });
    }

    private Path writeBinary(String name) {
        Path path = host.resolve(name);
        try {
            Files.writeString(path, "#!/bin/sh\n");
            path.toFile().setExecutable(true);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return path;
    }
}
