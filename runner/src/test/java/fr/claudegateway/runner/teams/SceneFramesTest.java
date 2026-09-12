package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>L'extraction aux changements de plan</b> (F-90 / SF-90-01).
 *
 * <p>Ce qui est prouvé ici : la ligne de commande passe ses arguments <b>un par un</b>, l'horodatage
 * vient du journal {@code showinfo} et non du numéro de fichier, une extraction ratée <b>jette</b>
 * ce qu'elle avait écrit, et zéro changement de plan rend <b>zéro image et un manque nommé</b>.</p>
 *
 * <p><b>Ce qui n'est pas prouvé, et qui est écrit</b> : qu'un vrai {@code ffmpeg}, sur une vraie
 * vidéo Teams, produise ce journal. Le CI n'a pas {@code ffmpeg} et nous n'avons aucun compte Teams
 * de test.</p>
 */
class SceneFramesTest {

    @TempDir
    Path home;

    @Test
    void la_commande_passe_ses_arguments_un_par_un_et_porte_le_seuil() throws IOException {
        FakeProcesses processes = new FakeProcesses();
        Path video = Files.createFile(home.resolve("reunion avec un espace.mp4"));
        Path out = home.resolve("frames");
        frames(processes.succeedingWith(List.of())).extract(video, out);

        List<String> command = processes.lastCall();
        assertTrue(command.contains("-nostdin"), "le runner n'a pas de terminal à prêter");
        int filter = command.indexOf("-vf");
        assertTrue(filter > 0);
        assertTrue(command.get(filter + 1).contains("gt(scene,0.400)"));
        assertTrue(command.get(filter + 1).contains("showinfo"));
        assertTrue(command.contains(video.toAbsolutePath().toString()),
                "le chemin voyage entier, jamais découpé par un shell");
    }

    @Test
    void lhorodatage_vient_du_journal_showinfo_et_non_du_numero_de_fichier() throws IOException {
        Path video = Files.createFile(home.resolve("reunion.mp4"));
        Path out = home.resolve("frames");
        Files.createDirectories(out);
        FakeProcesses processes = new FakeProcesses().answering(command -> {
            write(out, "frame-00001.jpg", "frame-00002.jpg", "frame-00003.jpg");
            return new ProcessRunner.ProcessResult(0, List.of(),
                    List.of(FakeProcesses.showinfo(0, 12.5), FakeProcesses.showinfo(1, 305.25),
                            FakeProcesses.showinfo(2, 1802.0)),
                    false);
        });

        SceneFrames.Extraction extraction = frames(processes).extract(video, out);

        assertEquals(3, extraction.frames().size());
        assertEquals(12.5, extraction.frames().get(0).offsetSeconds());
        assertEquals(305.25, extraction.frames().get(1).offsetSeconds());
        assertEquals("00:30:02", extraction.frames().get(2).describeOffset());
        assertTrue(extraction.gaps().isEmpty());
    }

    @Test
    void un_ecart_entre_images_et_horodatages_est_un_manque_nomme() throws IOException {
        Path video = Files.createFile(home.resolve("reunion.mp4"));
        Path out = home.resolve("frames");
        Files.createDirectories(out);
        FakeProcesses processes = new FakeProcesses().answering(command -> {
            write(out, "frame-00001.jpg", "frame-00002.jpg", "frame-00003.jpg");
            return new ProcessRunner.ProcessResult(0, List.of(),
                    List.of(FakeProcesses.showinfo(0, 1.0)), false);
        });

        SceneFrames.Extraction extraction = frames(processes).extract(video, out);

        assertEquals(1, extraction.frames().size(), "les images non datées sont écartées");
        assertEquals(1, extraction.gaps().size());
        assertTrue(extraction.gaps().get(0).describe().contains("mal datée"));
    }

    @Test
    void une_extraction_ratee_jette_ce_quelle_avait_ecrit() throws IOException {
        Path video = Files.createFile(home.resolve("reunion.mp4"));
        Path out = home.resolve("frames");
        Files.createDirectories(out);
        FakeProcesses processes = new FakeProcesses().answering(command -> {
            write(out, "frame-00001.jpg", "frame-00002.jpg");
            return new ProcessRunner.ProcessResult(1, List.of(),
                    List.of("[mov,mp4 @ 0x1] moov atom not found", "reunion.mp4: Invalid data"),
                    false);
        });

        SceneFrames.SceneExtractionException refusal =
                assertThrows(SceneFrames.SceneExtractionException.class,
                        () -> frames(processes).extract(video, out));

        assertEquals("extraction_failed", refusal.code());
        assertTrue(refusal.sentence().contains("moov atom not found"),
                "les dernières lignes de ffmpeg remontent, pas un « échec » nu");
        assertFalse(Files.exists(out.resolve("frame-00001.jpg")),
                "une extraction partielle donnerait un compte rendu plausible et faux");
    }

    @Test
    void une_video_absente_est_un_refus_nomme_avant_meme_de_chercher_ffmpeg() {
        FakeProcesses processes = new FakeProcesses();
        SceneFrames.SceneExtractionException refusal =
                assertThrows(SceneFrames.SceneExtractionException.class,
                        () -> frames(processes).extract(home.resolve("absente.mp4"),
                                home.resolve("frames")));

        assertEquals("not_found", refusal.code());
        assertTrue(refusal.detail().contains("je ne le télécharge pas depuis Teams"));
        assertTrue(processes.calls.isEmpty(), "on ne rapatrie pas ffmpeg pour rien");
    }

    @Test
    void zero_changement_de_plan_rend_zero_image_et_un_manque_nomme() throws IOException {
        Path video = Files.createFile(home.resolve("plan-fixe.mp4"));
        Path out = home.resolve("frames");
        SceneFrames.Extraction extraction =
                frames(new FakeProcesses().succeedingWith(List.of())).extract(video, out);

        FramesHarvest harvest = FrameSelection.select(extraction.frames(), extraction.gaps());

        assertTrue(harvest.frames().isEmpty());
        assertEquals(1, harvest.gaps().size());
        assertTrue(harvest.gaps().get(0).describe().contains("aucun changement de plan"));
        assertTrue(harvest.gaps().get(0).describe().contains("seuil"));
    }

    @Test
    void un_delai_depasse_est_dit_comme_tel() throws IOException {
        Path video = Files.createFile(home.resolve("longue.mp4"));
        FakeProcesses processes = new FakeProcesses().answering(command ->
                new ProcessRunner.ProcessResult(-1, List.of(), List.of("frame= 1200"), true));

        SceneFrames.SceneExtractionException refusal =
                assertThrows(SceneFrames.SceneExtractionException.class,
                        () -> frames(processes).extract(video, home.resolve("frames")));

        assertEquals("timeout", refusal.code());
        assertTrue(refusal.getMessage().contains("rien n'a été gardé"));
    }

    private SceneFrames frames(ProcessRunner processes) throws IOException {
        Path binary = home.resolve("ffmpeg");
        if (!Files.exists(binary)) {
            Files.createFile(binary);
            binary.toFile().setExecutable(true);
        }
        LocalToolchain toolchain = new LocalToolchain(new TeamsWorkFolder(home),
                OperatingSystem.LINUX,
                (command, dir, timeout) -> FakeProcesses.version("ffmpeg"),
                (url, into) -> 0L, message -> { }, name -> binary);
        return new SceneFrames(toolchain, processes);
    }

    private static void write(Path dir, String... names) {
        try {
            for (String name : names) {
                Files.writeString(dir.resolve(name), "jpeg");
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
