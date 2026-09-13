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
 * <b>Le test de la subfeature</b> (F-91 / SF-91-03) : <b>rien ne sort de la machine</b> — ni la
 * vidéo, ni l'audio, seulement le texte.
 *
 * <h2>Pourquoi celui-ci mérite son fichier</h2>
 *
 * <p>C'est la promesse qui décide de toute la conception : <i>« envoyer l'audio à un service
 * annulerait le bénéfice de garder la vidéo en local »</i>. Une promesse qu'on répète dans des
 * javadocs n'engage personne ; celle-ci est <b>vérifiée</b>. Toute adresse que le volet est capable
 * d'appeler est inspectée, et tout ce qu'un résultat d'outil emporte est relu octet par octet.</p>
 *
 * <p>Ce qui rend ce test tenable dans le temps : il ne liste pas les appels qu'on <b>fait</b>, il
 * interdit les données qu'on <b>emporte</b>. Un appel ajouté demain avec de l'audio dedans le fera
 * tomber, sans que personne ait à penser à le mettre à jour.</p>
 */
@DisplayName("F-91 / SF-91-03 — rien ne sort de la machine")
class RienNeSortDeLaMachineTest {

    @TempDir
    Path host;

    private TeamsWorkFolder folder;
    private FakeProcesses processes;
    private final List<String> said = new ArrayList<>();
    /** Toute adresse que le volet aurait demandé à rapatrier. */
    private final List<String> fetched = new ArrayList<>();

    @BeforeEach
    void setUp() {
        folder = new TeamsWorkFolder(host);
        processes = new FakeProcesses();
        said.clear();
        fetched.clear();
    }

    @Test
    @DisplayName("le SEUL trafic possible du chemin de transcription est le modèle, depuis une adresse EN DUR")
    void theOnlyTrafficIsTheModel() {
        LocalToolchain toolchain = new LocalToolchain(folder, OperatingSystem.LINUX, processes,
                (url, into) -> {
                    fetched.add(url);
                    Files.writeString(into, "modèle de papier");
                    return 16L;
                }, said::add, name -> null);

        // Le modèle : rapatrié, une fois, depuis l'adresse écrite dans LocalTool — jamais reçue
        // d'un appel d'outil.
        toolchain.require(LocalTool.transcriptionModel());

        assertEquals(1, fetched.size(), fetched.toString());
        assertTrue(fetched.get(0).startsWith("https://"), fetched.get(0));
        // Une adresse de téléchargement n'emporte RIEN de l'utilisateur : ni chemin de fichier, ni
        // identifiant de capture, ni fragment d'audio.
        assertFalse(fetched.get(0).contains("capture"), fetched.get(0));
        assertFalse(fetched.get(0).contains("?"), fetched.get(0));
    }

    @Test
    @DisplayName("le modèle rapatrié n'est JAMAIS lancé : c'est un fichier, pas un programme")
    void theModelIsNeverExecuted() {
        LocalToolchain toolchain = new LocalToolchain(folder, OperatingSystem.LINUX, processes,
                (url, into) -> {
                    Files.writeString(into, "modèle de papier");
                    return 16L;
                }, said::add, name -> {
                    throw new AssertionError("un fichier de données ne se cherche pas dans le PATH");
                });

        Path model = toolchain.require(LocalTool.transcriptionModel());

        assertTrue(model.toString().endsWith("ggml-base.bin"), model.toString());
        // Aucun « -version » : lui demander de s'identifier le ferait refuser à coup sûr.
        assertTrue(processes.calls.isEmpty(), processes.calls.toString());
    }

    @Test
    @DisplayName("ce que la transcription rend est du TEXTE — aucun octet d'audio, aucun de vidéo")
    void whatComesOutIsTextOnly() throws IOException {
        Path capture = Files.createDirectories(host.resolve("capture"));
        // De l'audio et de la vidéo de papier, avec une empreinte reconnaissable : si l'un d'eux se
        // retrouvait dans ce qui remonte, ce test tomberait.
        Files.write(capture.resolve("audio.wav"), "OCTETS-AUDIO-SECRETS".getBytes(StandardCharsets.UTF_8));
        Files.write(capture.resolve("capture.mp4"), "OCTETS-VIDEO-SECRETS".getBytes(StandardCharsets.UTF_8));
        Files.writeString(capture.resolve("transcript.csv"),
                "start,end,text\n0,1000,\"Bonjour à tous.\"\n", StandardCharsets.UTF_8);

        LocalTranscription.Result result = transcription(capture).transcribe(
                capture.resolve("audio.wav"), capture, Instant.parse("2026-09-13T14:32:00Z"),
                "Ce compte rendu provient d'un ENREGISTREMENT LOCAL.");

        String rendered = result.cues().toString() + result.gaps();
        assertFalse(rendered.contains("OCTETS-AUDIO"), rendered);
        assertFalse(rendered.contains("OCTETS-VIDEO"), rendered);
        assertTrue(rendered.contains("Bonjour à tous."), rendered);
    }

    @Test
    @DisplayName("l'audio et la vidéo RESTENT dans le dossier de la capture, sur cette machine")
    void theAudioAndVideoStayWhereTheyAre() throws IOException {
        Path capture = Files.createDirectories(host.resolve("capture"));
        Files.write(capture.resolve("audio.wav"), new byte[] {1, 2, 3});
        Files.write(capture.resolve("capture.mp4"), new byte[] {4, 5, 6});
        Files.writeString(capture.resolve("transcript.csv"),
                "0,1000,\"Bonjour.\"\n", StandardCharsets.UTF_8);

        transcription(capture).transcribe(capture.resolve("audio.wav"), capture,
                Instant.parse("2026-09-13T14:32:00Z"), "mention");

        assertTrue(Files.isRegularFile(capture.resolve("audio.wav")));
        assertTrue(Files.isRegularFile(capture.resolve("capture.mp4")));
        assertTrue(Files.isRegularFile(capture.resolve(LocalTranscription.READABLE)));
        assertTrue(fetched.isEmpty(), "rien ne doit avoir été envoyé nulle part : " + fetched);
    }

    private LocalTranscription transcription(Path capture) {
        Path engine = host.resolve("whisper-cli");
        Path model = folder.toolsDir().resolve("modele-de-transcription").resolve("ggml-base.bin");
        try {
            Files.writeString(engine, "#!/bin/sh\n");
            engine.toFile().setExecutable(true);
            Files.createDirectories(model.getParent());
            Files.writeString(model, "modèle de papier");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        processes.answering(command -> FakeProcesses.version("whisper-cli"));
        LocalToolchain toolchain = new LocalToolchain(folder, OperatingSystem.LINUX, processes,
                (url, into) -> {
                    fetched.add(url);
                    return 0L;
                }, said::add, name -> name.startsWith("whisper") ? engine : null);
        return new LocalTranscription(toolchain, processes);
    }
}
