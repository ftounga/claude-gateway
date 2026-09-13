package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>Le moteur de l'enregistrement local</b> (F-91 / SF-91-01).
 *
 * <p><b>Ce qui est prouvé ici, et ce qui ne l'est pas.</b> Prouvé : que le produit refuse sans
 * filigrane, refuse sans confirmation, refuse une seconde capture, assemble sa commande sans shell,
 * écrit son état, s'arrête proprement et constate un fichier vide. <b>Pas prouvé</b> : qu'un
 * {@code ffmpeg} réel capture l'écran et le son de chaque système — le CI n'a ni écran, ni son, ni
 * {@code ffmpeg}, et nous n'avons aucun compte Teams de test. La confrontation au réel reste le
 * travail de la sonde de santé (F-87 / SF-87-03) le jour du premier branchement.</p>
 */
@DisplayName("F-91 / SF-91-01 — le moteur de l'enregistrement local")
class LocalCaptureTest {

    @TempDir
    Path host;

    private TeamsWorkFolder folder;
    private CaptureStore store;
    private FakeProcesses processes;
    private FakeSessions sessions;
    private final List<String> said = new ArrayList<>();
    private Instant now = Instant.parse("2026-09-13T14:32:00Z");

    @BeforeEach
    void setUp() throws IOException {
        folder = new TeamsWorkFolder(host);
        store = new CaptureStore(folder);
        processes = new FakeProcesses();
        sessions = new FakeSessions();
        said.clear();
        Files.createDirectories(host.resolve("bin"));
    }

    // ------------------------------------------------------------------ le filigrane

    @Nested
    @DisplayName("Garde-fou n° 1 — la trace est indélébile")
    class Watermarking {

        @Test
        @DisplayName("aucune police : REFUS de capturer, et AUCUN processus lancé")
        void noFontNoCapture() {
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), noFont());

            CaptureRefusedException refused = assertThrows(CaptureRefusedException.class,
                    () -> capture.start(selfScreen()));

            assertEquals(CaptureRefusedException.NO_WATERMARK, refused.code());
            assertTrue(sessions.calls.isEmpty(), "rien ne doit avoir été lancé");
            assertFalse(refused.remedy().isBlank());
        }

        @Test
        @DisplayName("ffmpeg sans drawtext : REFUS de capturer, et AUCUN processus lancé")
        void noDrawtextNoCapture() {
            LocalCapture capture = capture(withFfmpegWithoutDrawtext(), someFont());

            CaptureRefusedException refused = assertThrows(CaptureRefusedException.class,
                    () -> capture.start(selfScreen()));

            assertEquals(CaptureRefusedException.NO_WATERMARK, refused.code());
            assertTrue(sessions.calls.isEmpty(), "rien ne doit avoir été lancé");
        }

        @Test
        @DisplayName("le filigrane est DANS le filtre passé à ffmpeg, donc incrusté dans l'image")
        void watermarkIsBurntIn() {
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), someFont());

            CaptureRecord record = capture.start(selfScreen());

            int index = sessions.lastCall().indexOf("-vf");
            assertTrue(index >= 0, sessions.lastCall().toString());
            String filter = sessions.lastCall().get(index + 1);
            assertTrue(filter.startsWith("drawtext="), filter);
            assertTrue(filter.contains("Enregistrement local"), filter);
            assertTrue(filter.contains("francky"), filter);
            // Et la même trace voyage avec le compte rendu.
            assertTrue(record.mention().contains("ENREGISTREMENT LOCAL"), record.mention());
        }
    }

    // ------------------------------------------------------------------ les deux gestes

    @Nested
    @DisplayName("Garde-fou n° 2 — deux usages, deux gestes")
    class TwoGestures {

        @Test
        @DisplayName("réunion sans confirmation : REFUS avant même de chercher ffmpeg")
        void meetingWithoutConfirmationTouchesNothing() {
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), someFont());

            CaptureRefusedException refused = assertThrows(CaptureRefusedException.class,
                    () -> capture.start(new LocalCapture.Request("meeting", null, true, "francky",
                            "Comité", "", "")));

            assertEquals(CaptureRefusedException.NOT_CONFIRMED, refused.code());
            // Un refus ne laisse rien derrière lui : ni processus, ni interrogation d'ffmpeg.
            assertTrue(sessions.calls.isEmpty());
            assertTrue(processes.calls.isEmpty());
        }

        @Test
        @DisplayName("son propre écran : démarre sans confirmation")
        void selfScreenStartsWithoutConfirmation() {
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), someFont());

            CaptureRecord record = capture.start(selfScreen());

            assertEquals(CapturePurpose.SELF_SCREEN, record.purpose());
            assertFalse(record.participantsInformed());
            assertEquals(1, sessions.calls.size());
        }

        @Test
        @DisplayName("réunion confirmée : démarre, et l'état garde la confirmation donnée")
        void meetingWithConfirmationStarts() {
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), someFont());

            CaptureRecord record = capture.start(new LocalCapture.Request("meeting", true, true,
                    "francky", "Comité", "", ""));

            assertEquals(CapturePurpose.MEETING_WITH_OTHERS, record.purpose());
            assertTrue(record.participantsInformed());
            assertTrue(String.join(" ", said).contains("de vive voix"));
        }
    }

    // ------------------------------------------------------------------ le témoin

    @Nested
    @DisplayName("Garde-fou n° 3 — le témoin est branché sur le moteur")
    class WitnessWiring {

        @Test
        @DisplayName("le témoin est montré au démarrage, effacé à l'arrêt")
        void witnessFollowsTheCapture() {
            AtomicReference<Runnable> stop = new AtomicReference<>();
            List<String> events = new ArrayList<>();
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), someFont())
                    .withWitness(new LocalCapture.Witness() {
                        @Override
                        public void show(CaptureRecord record, Runnable onStop) {
                            events.add("show:" + record.id());
                            stop.set(onStop);
                        }

                        @Override
                        public void hide() {
                            events.add("hide");
                        }
                    });

            CaptureRecord record = capture.start(selfScreen());
            assertEquals(List.of("show:" + record.id()), events);

            capture.stop(record.id());
            assertEquals(List.of("show:" + record.id(), "hide"), events);
        }

        @Test
        @DisplayName("le bouton du témoin arrête vraiment la capture, et ne lève jamais")
        void witnessButtonStops() {
            AtomicReference<Runnable> stop = new AtomicReference<>();
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), someFont())
                    .withWitness(new LocalCapture.Witness() {
                        @Override
                        public void show(CaptureRecord record, Runnable onStop) {
                            stop.set(onStop);
                        }

                        @Override
                        public void hide() {
                            // Rien.
                        }
                    });

            CaptureRecord record = capture.start(selfScreen());
            stop.get().run();

            assertTrue(capture.find(record.id()).orElseThrow().isOver());
            // Un second clic sur un témoin resté ouvert ne doit pas faire remonter d'exception.
            stop.get().run();
        }
    }

    // ------------------------------------------------------------------ le cycle

    @Nested
    @DisplayName("Démarrer, arrêter, et refuser")
    class Lifecycle {

        @Test
        @DisplayName("la vidéo est écrite dans le dossier du volet, jamais dans un chemin demandé")
        void videoLivesInTheWorkFolder() {
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), someFont());

            CaptureRecord record = capture.start(selfScreen());

            assertTrue(Path.of(record.video()).startsWith(folder.capturesDir()), record.video());
            assertTrue(record.video().endsWith("capture.mp4"), record.video());
        }

        @Test
        @DisplayName("une seconde capture est refusée, EN RENDANT celle qui tourne")
        void secondCaptureIsRefused() {
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), someFont());
            CaptureRecord first = capture.start(selfScreen());

            CaptureRefusedException refused = assertThrows(CaptureRefusedException.class,
                    () -> capture.start(selfScreen()));

            assertEquals(CaptureRefusedException.ALREADY_RUNNING, refused.code());
            assertTrue(refused.remedy().contains(first.id()), refused.remedy());
            assertEquals(1, sessions.calls.size());
        }

        @Test
        @DisplayName("ffmpeg meurt aussitôt : REFUS portant SES dernières lignes, pas un échec nu")
        void immediateDeathIsNamed() {
            sessions.dyingAtOnce(List.of("[x11grab] Cannot open display :0.0"));
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), someFont());

            CaptureRefusedException refused = assertThrows(CaptureRefusedException.class,
                    () -> capture.start(selfScreen()));

            assertEquals(CaptureRefusedException.NOT_STARTED, refused.code());
            assertTrue(refused.remedy().contains("Cannot open display"), refused.remedy());
            // Et rien ne reste en cours : la capture morte ne doit pas bloquer la suivante.
            assertTrue(capture.current().isEmpty());
        }

        @Test
        @DisplayName("ffmpeg introuvable : REFUS portant le remède de l'outillage")
        void missingToolIsNamed() {
            LocalToolchain toolchain = new LocalToolchain(folder, OperatingSystem.OTHER, processes,
                    (url, into) -> {
                        throw new IOException("hors ligne");
                    }, said::add, name -> null);
            LocalCapture capture = capture(toolchain, someFont());

            CaptureRefusedException refused = assertThrows(CaptureRefusedException.class,
                    () -> capture.start(selfScreen()));

            assertEquals(CaptureRefusedException.NO_TOOL, refused.code());
            assertTrue(sessions.calls.isEmpty());
        }

        @Test
        @DisplayName("l'arrêt est PROPRE d'abord : « q », et l'interruption seulement après")
        void stopIsGracefulFirst() {
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), someFont());
            CaptureRecord started = capture.start(selfScreen());

            now = now.plusSeconds(125);
            CaptureRecord stopped = capture.stop(started.id());

            assertTrue(sessions.lastHandle().stopRequested);
            assertFalse(sessions.lastHandle().destroyed, "on ne tue pas ce qui s'arrête proprement");
            assertEquals(CaptureRecord.State.TERMINEE, stopped.state());
            assertEquals("00:02:05", CaptureRecord.clock(stopped.elapsed(now)));
            assertTrue(stopped.bytes() > 0);
        }

        @Test
        @DisplayName("ffmpeg sourd à l'arrêt propre : interrompu, ET le manque est NOMMÉ")
        void deafProcessIsNamed() {
            sessions.deaf();
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), someFont());
            CaptureRecord started = capture.start(selfScreen());

            CaptureRecord stopped = capture.stop(started.id());

            assertTrue(sessions.lastHandle().destroyed);
            assertTrue(stopped.gaps().stream()
                    .anyMatch(gap -> gap.detail().contains("interrompu")), stopped.gaps().toString());
        }

        @Test
        @DisplayName("fichier vide à l'arrêt : ÉCHEC nommé, jamais un succès qui rend un chemin")
        void emptyFileIsAFailure() {
            sessions.producingNothing();
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), someFont());
            CaptureRecord started = capture.start(selfScreen());

            CaptureRecord stopped = capture.stop(started.id());

            assertEquals(CaptureRecord.State.ECHOUEE, stopped.state());
            assertTrue(stopped.failure().contains("aucun fichier exploitable"), stopped.failure());
        }

        @Test
        @DisplayName("arrêter sans identifiant arrête celle qui tourne")
        void stopWithoutIdStopsTheRunningOne() {
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), someFont());
            CaptureRecord started = capture.start(selfScreen());

            assertEquals(started.id(), capture.stop("").id());
        }

        @Test
        @DisplayName("arrêter alors que rien ne tourne : REFUS, sans rien inventer")
        void stopWithNothingRunning() {
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), someFont());

            CaptureRefusedException refused =
                    assertThrows(CaptureRefusedException.class, () -> capture.stop(""));

            assertEquals(CaptureRefusedException.UNKNOWN, refused.code());
        }

        @Test
        @DisplayName("arrêter deux fois rend l'état final, ce n'est pas une erreur")
        void stoppingTwiceIsIdempotent() {
            LocalCapture capture = capture(withFfmpegKnowingDrawtext(), someFont());
            CaptureRecord started = capture.start(selfScreen());
            capture.stop(started.id());

            assertEquals(CaptureRecord.State.TERMINEE, capture.stop(started.id()).state());
        }
    }

    // ------------------------------------------------------------------ la mémoire

    @Nested
    @DisplayName("L'état survit au redémarrage du runner")
    class Memory {

        @Test
        @DisplayName("une capture en cours est retrouvée par un moteur neuf")
        void runningCaptureIsFoundAgain() {
            capture(withFfmpegKnowingDrawtext(), someFont()).start(selfScreen());

            LocalCapture reborn = capture(withFfmpegKnowingDrawtext(), someFont());

            CaptureRecord found = reborn.current().orElseThrow();
            assertEquals(CaptureRecord.State.EN_COURS, found.state());
            // Le filigrane est RELU tel qu'il a été incrusté, jamais recalculé : l'heure doit
            // rester celle qui est dans l'image.
            assertTrue(found.watermark().contains("13/09/2026 14:32"), found.watermark());
        }

        @Test
        @DisplayName("un état illisible n'est pas un état : il est dit absent, pas deviné")
        void unreadableStateIsAbsent() throws IOException {
            CaptureRecord record =
                    capture(withFfmpegKnowingDrawtext(), someFont()).start(selfScreen());
            Files.writeString(folder.capturesDir().resolve(record.id()).resolve("capture.json"),
                    "{ pas du json");

            assertTrue(new CaptureStore(folder).find(record.id()).isEmpty());
        }
    }

    // ------------------------------------------------------------------ montage

    private LocalCapture capture(LocalToolchain toolchain, WatermarkFont fonts) {
        return new LocalCapture(folder, OperatingSystem.LINUX, toolchain, processes, sessions, store,
                fonts, millis -> { }, () -> now, said::add, () -> ":0.0");
    }

    /** Un {@code ffmpeg} déjà présent sur le PATH, qui répond « je connais drawtext ». */
    private LocalToolchain withFfmpegKnowingDrawtext() {
        return toolchainAnswering(List.of(
                " T.. drawtext          V->V       Draw text on top of video frames."));
    }

    /** Une build minimale : le filtre manque. */
    private LocalToolchain withFfmpegWithoutDrawtext() {
        return toolchainAnswering(List.of(" T.. drawbox           V->V       Draw a box."));
    }

    private LocalToolchain toolchainAnswering(List<String> filters) {
        Path fake = host.resolve("bin").resolve("ffmpeg");
        try {
            Files.writeString(fake, "#!/bin/sh\n");
            fake.toFile().setExecutable(true);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        processes.answering(command -> command.contains("-filters")
                ? new ProcessRunner.ProcessResult(0, filters, List.of(), false)
                : FakeProcesses.version("ffmpeg"));
        return new LocalToolchain(folder, OperatingSystem.LINUX, processes,
                (url, into) -> 0L, said::add, name -> fake);
    }

    /** Le poste a DejaVu, comme la quasi-totalité des postes Linux. */
    private WatermarkFont someFont() {
        return new WatermarkFont(OperatingSystem.LINUX,
                path -> path.toString().endsWith("DejaVuSans.ttf"));
    }

    /** Le poste n'a aucune des polices connues : c'est ce qui fera refuser la capture. */
    private WatermarkFont noFont() {
        return new WatermarkFont(OperatingSystem.LINUX, path -> false);
    }

    private static LocalCapture.Request selfScreen() {
        return new LocalCapture.Request("self", null, true, "francky", "", "", "");
    }
}
