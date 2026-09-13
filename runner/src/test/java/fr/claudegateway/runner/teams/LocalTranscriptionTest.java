package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>La transcription sur la machine</b> (F-91 / SF-91-03).
 *
 * <p><b>Provenance des échantillons, écrite et non maquillée</b> : le format {@code start,end,text}
 * en millisecondes est celui <b>documenté</b> par le moteur. Les lignes ci-dessous sont
 * <b>fabriquées à la main</b> — le CI n'a ni le binaire, ni le modèle, ni de réunion à transcrire.
 * Ce qui est prouvé est donc ce que le produit <b>décide</b> : lire, dater, refuser, écrire la
 * mention. Pas la qualité d'une transcription réelle.</p>
 */
@DisplayName("F-91 / SF-91-03 — la transcription sur la machine")
class LocalTranscriptionTest {

    private static final Instant STARTED = Instant.parse("2026-09-13T14:32:00Z");

    @TempDir
    Path host;

    private TeamsWorkFolder folder;
    private FakeProcesses processes;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        folder = new TeamsWorkFolder(host);
        processes = new FakeProcesses();
        said.clear();
    }

    @Nested
    @DisplayName("Lire ce que le moteur a écrit")
    class Reading {

        @Test
        @DisplayName("les répliques sont datées EN ABSOLU, à partir de l'instant du démarrage")
        void cuesAreDatedFromTheCaptureStart() {
            LocalTranscription.Result result = LocalTranscription.read(List.of(
                    "start,end,text",
                    "0,2400,\"Bonjour à tous.\"",
                    "2400,7100,\"On reprend le point budget.\""), STARTED);

            assertEquals(2, result.cues().size());
            assertEquals(STARTED, result.cues().get(0).at());
            assertEquals(STARTED.plusMillis(2_400), result.cues().get(1).at());
            assertEquals("On reprend le point budget.", result.cues().get(1).text());
        }

        @Test
        @DisplayName("le moteur local ne dit PAS qui parle, et le manque est NOMMÉ")
        void theSpeakerIsNeverGuessed() {
            LocalTranscription.Result result =
                    LocalTranscription.read(List.of("0,1000,\"Bonjour.\""), STARTED);

            assertEquals("", result.cues().get(0).speakerDisplayName());
            assertTrue(result.gaps().stream()
                    .anyMatch(gap -> gap.detail().contains("ne dit pas QUI parle")),
                    result.gaps().toString());
        }

        @Test
        @DisplayName("une LIGNE illisible est écartée, comptée et nommée — les autres sont rendues")
        void oneUnreadableLineIsCounted() {
            LocalTranscription.Result result = LocalTranscription.read(List.of(
                    "0,1000,\"Bonjour.\"",
                    "pas,du,tout",
                    "1000,2000,\"La suite.\""), STARTED);

            assertEquals(2, result.cues().size());
            assertEquals(1, result.unreadable());
            assertTrue(result.gaps().stream()
                    .anyMatch(gap -> gap.kind() == TeamsGapKind.UNRECOGNIZED_PAYLOAD));
        }

        @Test
        @DisplayName("un FORMAT entier inconnu fait ÉCHOUER : jamais la moitié des répliques")
        void anUnknownFormatFails() {
            LocalTranscription.TranscriptionFailedException failed = assertThrows(
                    LocalTranscription.TranscriptionFailedException.class,
                    () -> LocalTranscription.read(List.of("start,end,text"), STARTED));

            assertTrue(failed.getMessage().contains("format que je ne reconnais pas"),
                    failed.getMessage());
            assertTrue(failed.detail().contains("plausible et faux"), failed.detail());
        }

        @Test
        @DisplayName("un fichier vide fait ÉCHOUER : « rien dit » et « rien lu » ne se confondent pas")
        void anEmptyFileFails() {
            assertThrows(LocalTranscription.TranscriptionFailedException.class,
                    () -> LocalTranscription.read(List.of(), STARTED));
        }

        @Test
        @DisplayName("zéro réplique reconnue rend zéro ET un manque nommé, jamais un silence")
        void noSpeechIsNamed() {
            LocalTranscription.Result result = LocalTranscription.read(List.of(
                    "0,1000,\"\"", "1000,2000,\"\""), STARTED);

            assertTrue(result.cues().isEmpty());
            assertTrue(result.gaps().stream()
                    .anyMatch(gap -> gap.detail().contains("ne veut pas dire que personne n'a parlé")),
                    result.gaps().toString());
        }

        @Test
        @DisplayName("les guillemets doublés du CSV sont rendus tels que le locuteur les a dits")
        void csvQuotesAreUnescaped() {
            assertEquals("il a dit \"non\"",
                    LocalTranscription.unquote("\"il a dit \"\"non\"\"\""));
        }
    }

    @Nested
    @DisplayName("Ce qui tourne, et ce qui est écrit")
    class Running {

        @Test
        @DisplayName("la commande demande le CSV : c'est lui qui porte les HORODATAGES")
        void commandAsksForTimestamps() {
            List<String> command = LocalTranscription.command(Path.of("/usr/bin/whisper-cli"),
                    Path.of("/m/ggml-base.bin"), Path.of("/c/audio.wav"), Path.of("/c/transcript"));

            assertTrue(command.contains("-ocsv"), command.toString());
            assertTrue(command.contains("/m/ggml-base.bin"), command.toString());
            // Sans horodatage, aucune réplique ne peut être posée à côté de son image : F-90 ne
            // servirait plus à rien.
            assertFalse(command.contains("-otxt"), command.toString());
        }

        @Test
        @DisplayName("le fichier lisible porte LA MENTION EN PREMIÈRE LIGNE")
        void theReadableFileCarriesTheMentionFirst() throws IOException {
            Path capture = Files.createDirectories(host.resolve("capture"));
            Files.writeString(capture.resolve("transcript.csv"),
                    "start,end,text\n0,1000,\"Bonjour.\"\n", StandardCharsets.UTF_8);
            LocalTranscription transcription = transcriptionThatSucceeds();

            transcription.transcribe(capture.resolve("audio.wav"), capture, STARTED,
                    "Ce compte rendu provient d'un ENREGISTREMENT LOCAL.");

            List<String> written = Files.readAllLines(
                    capture.resolve(LocalTranscription.READABLE), StandardCharsets.UTF_8);
            assertEquals("Ce compte rendu provient d'un ENREGISTREMENT LOCAL.", written.get(0));
            assertTrue(written.get(2).contains("Bonjour."), written.toString());
        }

        @Test
        @DisplayName("le moteur en échec rend SES dernières lignes, jamais un « échec » nu")
        void engineFailureCarriesItsLines() throws IOException {
            Path capture = Files.createDirectories(host.resolve("capture"));
            processes.answering(command -> command.contains("-ocsv")
                    ? new ProcessRunner.ProcessResult(1, List.of(),
                            List.of("error: failed to load model"), false)
                    : FakeProcesses.version("whisper-cli"));
            LocalTranscription transcription =
                    new LocalTranscription(toolchain(), processes);

            LocalTranscription.TranscriptionFailedException failed = assertThrows(
                    LocalTranscription.TranscriptionFailedException.class,
                    () -> transcription.transcribe(capture.resolve("audio.wav"), capture, STARTED,
                            "mention"));

            assertTrue(failed.detail().contains("failed to load model"), failed.detail());
        }

        @Test
        @DisplayName("le moteur qui n'écrit pas son fichier fait ÉCHOUER, avec la raison")
        void aMissingOutputFails() throws IOException {
            Path capture = Files.createDirectories(host.resolve("capture"));
            LocalTranscription transcription = transcriptionThatSucceeds();

            LocalTranscription.TranscriptionFailedException failed = assertThrows(
                    LocalTranscription.TranscriptionFailedException.class,
                    () -> transcription.transcribe(capture.resolve("audio.wav"), capture, STARTED,
                            "mention"));

            assertTrue(failed.getMessage().contains("n'a pas écrit le fichier attendu"),
                    failed.getMessage());
        }
    }

    @Nested
    @DisplayName("L'audio, et le fait qu'il ne sorte pas")
    class Audio {

        @Test
        @DisplayName("la commande produit du mono 16 kHz, sans vidéo, sans shell")
        void audioCommandIsWhatAnEngineExpects() {
            List<String> command = AudioTrack.command(Path.of("/usr/bin/ffmpeg"),
                    Path.of("/c/capture.mp4"), Path.of("/c/audio.wav"));

            assertTrue(command.contains("-vn"), command.toString());
            assertTrue(consecutive(command, "-ac", "1"), command.toString());
            assertTrue(consecutive(command, "-ar", AudioTrack.SAMPLE_RATE), command.toString());
            assertEquals("/c/audio.wav", command.get(command.size() - 1));
        }

        @Test
        @DisplayName("une vidéo absente : refus nommé, sans lancer ffmpeg")
        void aMissingVideoIsRefused() {
            AudioTrack audio = new AudioTrack(toolchain(), processes);

            AudioTrack.AudioUnavailableException failed =
                    assertThrows(AudioTrack.AudioUnavailableException.class,
                            () -> audio.extract(host.resolve("absent.mp4"), host));

            assertTrue(failed.getMessage().contains("Je ne trouve pas l'enregistrement"));
            assertTrue(processes.calls.isEmpty());
        }

        @Test
        @DisplayName("ffmpeg en échec : les images partielles sont jetées, ses lignes sont rendues")
        void aFailedExtractionKeepsNothing() throws IOException {
            Path video = host.resolve("capture.mp4");
            Files.writeString(video, "video de papier");
            processes.answering(command -> command.contains("-vn")
                    ? new ProcessRunner.ProcessResult(1, List.of(),
                            List.of("Output file #0 does not contain any stream"), false)
                    : FakeProcesses.version("ffmpeg"));
            AudioTrack audio = new AudioTrack(toolchain(), processes);

            AudioTrack.AudioUnavailableException failed = assertThrows(
                    AudioTrack.AudioUnavailableException.class,
                    () -> audio.extract(video, host.resolve("work")));

            assertTrue(failed.detail().contains("does not contain any stream"), failed.detail());
            assertFalse(Files.exists(host.resolve("work").resolve("audio.wav")));
        }
    }

    // ------------------------------------------------------------------ montages

    private LocalTranscription transcriptionThatSucceeds() {
        processes.answering(command -> FakeProcesses.version(
                command.stream().anyMatch(argument -> argument.contains("whisper"))
                        ? "whisper-cli" : "ffmpeg"));
        return new LocalTranscription(toolchain(), processes);
    }

    /** Le moteur et le modèle sont déjà là : rien n'est téléchargé dans un test. */
    private LocalToolchain toolchain() {
        Path engine = host.resolve("whisper-cli");
        Path model = host.resolve("ggml-base.bin");
        try {
            Files.writeString(engine, "#!/bin/sh\n");
            engine.toFile().setExecutable(true);
            Files.writeString(model, "modèle de papier");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Path tools = folder.toolsDir().resolve("modele-de-transcription");
        try {
            Files.createDirectories(tools);
            Files.copy(model, tools.resolve("ggml-base.bin"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new LocalToolchain(folder, OperatingSystem.LINUX, processes, (url, into) -> {
            throw new IOException("aucun téléchargement ne doit avoir lieu dans ce test");
        }, said::add, name -> name.startsWith("whisper") || name.equals("ffmpeg") ? engine : null);
    }

    private static boolean consecutive(List<String> command, String first, String second) {
        for (int index = 0; index + 1 < command.size(); index++) {
            if (command.get(index).equals(first) && command.get(index + 1).equals(second)) {
                return true;
            }
        }
        return false;
    }
}
