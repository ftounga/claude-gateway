package fr.claudegateway.runner.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.PathResolver;
import fr.claudegateway.runner.ToolContext;
import fr.claudegateway.runner.ToolException;
import fr.claudegateway.runner.ToolOutcome;
import fr.claudegateway.runner.teams.ProcessRunner;
import fr.claudegateway.runner.teams.SceneFrames;
import fr.claudegateway.runner.teams.VideoFixtures;

/**
 * <b>Regarder une vidéo</b> (F-190 / SF-190-01) : les deux outils, de la demande à la liste
 * d'images que l'agent ouvrira par {@code read_file}.
 *
 * <p>Ce qui n'est pas prouvé ici, et qui est écrit : qu'un vrai {@code ffmpeg} produise ce journal
 * {@code showinfo} — le CI n'en a pas. Ce point est vérifié à la main sur un poste (SF-190-04).</p>
 */
class VideoToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    @Test
    void le_mode_plans_liste_les_images_horodatees_lisibles_par_read_file() throws IOException {
        Path video = deposit("Demo Monday.mp4");
        ProcessRunner ffmpeg = writing(3, 12.5, 305.0, 1802.0);
        VideoTools tools = tools(VideoFixtures.frames(home, ffmpeg), inline());

        String started = ok(tools.execute(VideoTools.IMAGES, input("path", ".atelier/entrees/Demo Monday.mp4"),
                ToolContext.none(), paths()));
        String jobId = jobId(started);
        String listed = ok(tools.execute(VideoTools.STATUS, input("job_id", jobId),
                ToolContext.none(), paths()));

        assertTrue(listed.contains("étape : Terminé"), listed);
        assertTrue(listed.contains("Planche 1 : .atelier/video/demo-monday-"), listed);
        assertTrue(listed.contains("case 1 · 00:00:12 · .atelier/video/demo-monday-"), listed);
        assertTrue(listed.indexOf("Planche 1") < listed.indexOf("case 1"), "les planches d'abord");
        assertTrue(listed.contains("00:05:05"), listed);
        assertTrue(listed.contains("00:30:02"), listed);
        assertTrue(listed.contains("read_file"), "l'agent doit savoir comment les voir");
        String firstPath = listed.lines().filter(line -> line.contains("case 1 · "))
                .findFirst().orElseThrow().split(" · ")[2].strip();
        String sheetPath = listed.lines().filter(line -> line.startsWith("Planche 1 : "))
                .findFirst().orElseThrow().substring("Planche 1 : ".length()).strip();
        assertTrue(Files.isRegularFile(home.resolve(sheetPath)), "la planche existe : " + sheetPath);
        assertTrue(Files.isRegularFile(home.resolve(firstPath)),
                "le chemin rendu est celui que read_file lira : " + firstPath);
        assertTrue(Files.exists(video), "la vidéo reste où elle est");
    }

    @Test
    void les_images_ecartees_sont_effacees_du_disque() throws IOException {
        deposit("plan-fixe.mp4");
        // Trois sorties, dont deux identiques à la précédente : une seule survit au tri.
        ProcessRunner ffmpeg = (command, dir, timeout) -> {
            Path out = Path.of(command.get(command.size() - 1)).getParent();
            VideoFixtures.writeDistinctFrames(out, 1);
            try {
                Files.copy(out.resolve("frame-00001.jpg"), out.resolve("frame-00002.jpg"));
                Files.copy(out.resolve("frame-00001.jpg"), out.resolve("frame-00003.jpg"));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return result(10.0, 20.0, 30.0);
        };
        VideoTools tools = tools(VideoFixtures.frames(home, ffmpeg), inline());

        String status = statusOf(tools, ok(tools.execute(VideoTools.IMAGES,
                input("path", ".atelier/entrees/plan-fixe.mp4"), ToolContext.none(), paths())));

        assertTrue(status.contains("1 image retenue sur 3"), status);
        try (var files = Files.walk(home.resolve(".atelier/video"))) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().startsWith("frame-"))
                    .count(), "les doublons ne restent pas sur le disque");
        }
    }

    @Test
    void le_mode_intervalle_date_en_temps_absolu() throws IOException {
        deposit("demo.mp4");
        List<List<String>> calls = new ArrayList<>();
        ProcessRunner ffmpeg = (command, dir, timeout) -> {
            calls.add(command);
            VideoFixtures.writeDistinctFrames(Path.of(command.get(command.size() - 1)).getParent(), 2);
            return result(0.0, 5.0);
        };
        VideoTools tools = tools(VideoFixtures.frames(home, ffmpeg), inline());
        ObjectNode request = input("path", ".atelier/entrees/demo.mp4");
        request.put("mode", "intervalle").put("from", "00:12:30").put("to", "00:12:40")
                .put("every_seconds", 5);

        String status = statusOf(tools, ok(tools.execute(VideoTools.IMAGES, request,
                ToolContext.none(), paths())));

        assertTrue(status.contains(" · 00:12:30 · "), status);
        assertTrue(status.contains(" · 00:12:35 · "), status);
        assertTrue(status.contains("/zoom-750-760-5/"), status);
        List<String> command = calls.get(0);
        assertEquals("750.000", command.get(command.indexOf("-ss") + 1));
        assertEquals("760.000", command.get(command.indexOf("-to") + 1));
        assertTrue(command.get(command.indexOf("-vf") + 1).startsWith("fps=1/5.000,showinfo"));
    }

    @Test
    void un_intervalle_de_plus_de_soixante_images_est_refuse_avec_un_pas_suggere() throws IOException {
        deposit("demo.mp4");
        VideoTools tools = tools(VideoFixtures.frames(home, writing(0)), inline());
        ObjectNode request = input("path", ".atelier/entrees/demo.mp4");
        request.put("mode", "intervalle").put("from", 0).put("to", 600).put("every_seconds", 5);

        ToolOutcome outcome = tools.execute(VideoTools.IMAGES, request, ToolContext.none(), paths());

        assertFalse(outcome.ok());
        assertTrue(outcome.errorMessage().contains("121 images"), outcome.errorMessage());
        assertTrue(outcome.errorMessage().contains("every_seconds à 11"), outcome.errorMessage());
    }

    @Test
    void un_intervalle_a_lenvers_est_refuse() {
        assertThrows(ToolException.class, () -> VideoTools.checkInterval(30, 10, 5));
        assertThrows(ToolException.class, () -> VideoTools.checkInterval(0, 10, 0));
    }

    @Test
    void la_meme_demande_ne_relance_pas_le_travail_et_restart_le_relance() throws IOException {
        deposit("demo.mp4");
        AtomicInteger runs = new AtomicInteger();
        ProcessRunner ffmpeg = (command, dir, timeout) -> {
            runs.incrementAndGet();
            VideoFixtures.writeDistinctFrames(Path.of(command.get(command.size() - 1)).getParent(), 1);
            return result(1.0);
        };
        VideoTools tools = tools(VideoFixtures.frames(home, ffmpeg), inline());
        ObjectNode request = input("path", ".atelier/entrees/demo.mp4");

        String first = ok(tools.execute(VideoTools.IMAGES, request, ToolContext.none(), paths()));
        String again = ok(tools.execute(VideoTools.IMAGES, request, ToolContext.none(), paths()));
        request.put("restart", true);
        tools.execute(VideoTools.IMAGES, request, ToolContext.none(), paths());

        assertEquals(jobId(first), jobId(again), "même vidéo, mêmes réglages : même travail");
        assertEquals(2, runs.get(), "une reprise, puis une seule relance demandée");
    }

    @Test
    void un_travail_termine_se_relit_apres_redemarrage_du_runner() throws IOException {
        deposit("demo.mp4");
        ProcessRunner ffmpeg = writing(2, 3.0, 9.0);
        String jobId = jobId(ok(tools(VideoFixtures.frames(home, ffmpeg), inline())
                .execute(VideoTools.IMAGES, input("path", ".atelier/entrees/demo.mp4"),
                        ToolContext.none(), paths())));

        VideoTools restarted = tools(VideoFixtures.frames(home, ffmpeg), inline());
        String status = ok(restarted.execute(VideoTools.STATUS, input("job_id", jobId),
                ToolContext.none(), paths()));

        assertTrue(status.contains("étape : Terminé"), status);
        assertTrue(status.contains("case 2 · 00:00:09 · "), status);
        assertTrue(status.contains("Planche 1 : "), "les planches survivent au redémarrage : " + status);
    }

    @Test
    void sans_ffmpeg_le_travail_echoue_avec_le_remede() throws IOException {
        deposit("demo.mp4");
        VideoTools tools = tools(VideoFixtures.withoutFfmpeg(home, writing(0)), inline());

        String status = statusOf(tools, ok(tools.execute(VideoTools.IMAGES,
                input("path", ".atelier/entrees/demo.mp4"), ToolContext.none(), paths())));

        assertTrue(status.contains("étape : Échec"), status);
        assertTrue(status.contains("Remède : "), status);
        assertTrue(status.contains("tu n'as PAS vu la vidéo"), status);
    }

    @Test
    void loutil_rend_la_main_tout_de_suite_pendant_lextraction() throws Exception {
        deposit("longue.mp4");
        CountDownLatch release = new CountDownLatch(1);
        ProcessRunner slow = (command, dir, timeout) -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            VideoFixtures.writeDistinctFrames(Path.of(command.get(command.size() - 1)).getParent(), 1);
            return result(1.0);
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            VideoTools tools = tools(VideoFixtures.frames(home, slow), pool);
            List<String> streamed = new ArrayList<>();
            ToolContext context = new ToolContext() {
                @Override
                public void stream(String stream, String chunk) {
                    streamed.add(chunk);
                }

                @Override
                public long timeoutMs() {
                    return 20_000L;
                }

                @Override
                public boolean cancelled() {
                    return false;
                }
            };
            String started = ok(tools.execute(VideoTools.IMAGES,
                    input("path", ".atelier/entrees/longue.mp4"), context, paths()));

            assertFalse(started.contains("Terminé"), started);
            assertTrue(started.contains("video_images_status"), started);
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(streamed.stream().anyMatch(chunk -> chunk.startsWith("Extraction")),
                    "les étapes sont diffusées : " + streamed);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void un_chemin_absent_ou_introuvable_et_un_job_inconnu_sont_nommes() throws IOException {
        VideoTools tools = tools(VideoFixtures.frames(home, writing(0)), inline());

        ToolOutcome noPath = tools.execute(VideoTools.IMAGES, JSON.createObjectNode(),
                ToolContext.none(), paths());
        ToolOutcome missing = tools.execute(VideoTools.IMAGES, input("path", "absente.mp4"),
                ToolContext.none(), paths());
        ToolOutcome unknown = tools.execute(VideoTools.STATUS, input("job_id", "0123456789abcdef"),
                ToolContext.none(), paths());

        assertFalse(noPath.ok());
        assertTrue(noPath.errorMessage().contains("chemin de la vidéo"), noPath.errorMessage());
        assertFalse(missing.ok());
        assertTrue(missing.errorMessage().contains("Je ne trouve pas de fichier vidéo"), missing.errorMessage());
        assertFalse(missing.errorMessage().contains("Teams"), "message générique");
        assertFalse(unknown.ok());
        assertTrue(unknown.errorMessage().contains("Relance video_images"), unknown.errorMessage());
    }

    @Test
    void les_heures_se_lisent_en_secondes_ou_en_horloge() {
        assertEquals(750d, VideoTools.seconds(input("t", "00:12:30"), "t"));
        assertEquals(90d, VideoTools.seconds(input("t", "1:30"), "t"));
        assertEquals(42.5d, VideoTools.seconds(input("t", "42,5"), "t"));
    }

    @Test
    void une_transcription_fournie_pose_ce_qui_se_disait_a_cote_de_chaque_image() throws IOException {
        deposit("demo.mp4");
        Files.writeString(home.resolve(".atelier/entrees/demo.vtt"), """
                WEBVTT

                00:00:01.000 --> 00:00:02.000
                <v Avant>Je parle avant la première image.</v>

                00:00:12.000 --> 00:00:14.000
                <v Mickaël>Voici le board principal.</v>

                00:00:20.000 --> 00:00:22.000
                <v Mickaël>Avec ses cinq groupes.</v>

                00:05:06.000 --> 00:05:08.000
                <v Francky>Et Alarm for Tech ?</v>
                """);
        VideoTools tools = tools(VideoFixtures.frames(home, writing(2, 10.0, 305.0)), inline());
        ObjectNode request = input("path", ".atelier/entrees/demo.mp4");
        request.put("transcript", ".atelier/entrees/demo.vtt");

        String status = statusOf(tools, ok(tools.execute(VideoTools.IMAGES, request,
                ToolContext.none(), paths())));

        assertTrue(status.contains("dit : « Mickaël : Voici le board principal. / Mickaël : Avec ses cinq groupes. »"),
                status);
        assertTrue(status.contains("dit : « Francky : Et Alarm for Tech ? »"), status);
        assertTrue(status.contains("1 réplique avant la première image"), status);
        assertTrue(status.contains("Transcription complète : .atelier/entrees/demo.vtt"), status);
    }

    @Test
    void une_transcription_sans_horodatage_laisse_les_images_et_nomme_le_manque() throws IOException {
        deposit("demo.mp4");
        Files.writeString(home.resolve(".atelier/entrees/notes.txt"), "Rien de daté.");
        VideoTools tools = tools(VideoFixtures.frames(home, writing(1, 4.0)), inline());
        ObjectNode request = input("path", ".atelier/entrees/demo.mp4");
        request.put("transcript", ".atelier/entrees/notes.txt");

        String status = statusOf(tools, ok(tools.execute(VideoTools.IMAGES, request,
                ToolContext.none(), paths())));

        assertTrue(status.contains("étape : Terminé"), status);
        assertTrue(status.contains("sans horodatage lisible"), status);
        assertFalse(status.contains("dit : "), status);
    }

    @Test
    void transcrire_sur_le_poste_aligne_le_texte_produit() throws IOException {
        deposit("demo.mp4");
        VideoTranscriber fake = (video, dir) -> {
            try {
                Path readable = Files.writeString(dir.resolve("transcript.txt"), "texte complet");
                return new VideoTranscriber.Transcribed(
                        List.of(new TimedTranscript.Line(5.0, "", "Bienvenue dans Monday.")), readable);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        VideoTools tools = new VideoTools(new VideoFramesWorker(
                VideoFixtures.frames(home, writing(1, 2.0)), fake, inline()));
        ObjectNode request = input("path", ".atelier/entrees/demo.mp4");
        request.put("transcribe", true);

        String status = statusOf(tools, ok(tools.execute(VideoTools.IMAGES, request,
                ToolContext.none(), paths())));

        assertTrue(status.contains("dit : « Bienvenue dans Monday. »"), status);
        assertTrue(status.contains("Transcription complète : .atelier/video/"), status);
    }

    @Test
    void un_transcripteur_indisponible_laisse_les_images_et_donne_le_remede() throws IOException {
        deposit("demo.mp4");
        VideoTranscriber missing = (video, dir) -> {
            throw new VideoTranscriber.Unavailable("whisper.cpp introuvable", "brew install whisper-cpp");
        };
        VideoTools tools = new VideoTools(new VideoFramesWorker(
                VideoFixtures.frames(home, writing(1, 2.0)), missing, inline()));
        ObjectNode request = input("path", ".atelier/entrees/demo.mp4");
        request.put("transcribe", true);

        String status = statusOf(tools, ok(tools.execute(VideoTools.IMAGES, request,
                ToolContext.none(), paths())));

        assertTrue(status.contains("étape : Terminé"), status);
        assertTrue(status.contains("whisper.cpp introuvable — brew install whisper-cpp"), status);
    }

    @Test
    void changer_la_source_du_son_refait_le_travail() throws IOException {
        deposit("demo.mp4");
        Files.writeString(home.resolve(".atelier/entrees/demo.vtt"), "WEBVTT\n");
        AtomicInteger runs = new AtomicInteger();
        ProcessRunner ffmpeg = (command, dir, timeout) -> {
            runs.incrementAndGet();
            VideoFixtures.writeDistinctFrames(Path.of(command.get(command.size() - 1)).getParent(), 1);
            return result(1.0);
        };
        VideoTools tools = tools(VideoFixtures.frames(home, ffmpeg), inline());
        ObjectNode silent = input("path", ".atelier/entrees/demo.mp4");
        ObjectNode withSound = input("path", ".atelier/entrees/demo.mp4");
        withSound.put("transcript", ".atelier/entrees/demo.vtt");

        String first = ok(tools.execute(VideoTools.IMAGES, silent, ToolContext.none(), paths()));
        String second = ok(tools.execute(VideoTools.IMAGES, withSound, ToolContext.none(), paths()));
        ok(tools.execute(VideoTools.IMAGES, withSound, ToolContext.none(), paths()));

        assertFalse(jobId(first).equals(jobId(second)), "une autre source de son, un autre travail");
        assertEquals(2, runs.get(), "la même demande avec le son est reprise, pas refaite");
    }

    @Test
    void le_son_aligne_survit_au_redemarrage_du_runner() throws IOException {
        deposit("demo.mp4");
        Files.writeString(home.resolve(".atelier/entrees/demo.vtt"),
                "WEBVTT\n\n00:00:03.000 --> 00:00:04.000\n<v Mickaël>Le board RH.</v>\n");
        ProcessRunner ffmpeg = writing(1, 1.0);
        ObjectNode request = input("path", ".atelier/entrees/demo.mp4");
        request.put("transcript", ".atelier/entrees/demo.vtt");
        String jobId = jobId(ok(tools(VideoFixtures.frames(home, ffmpeg), inline())
                .execute(VideoTools.IMAGES, request, ToolContext.none(), paths())));

        String status = ok(tools(VideoFixtures.frames(home, ffmpeg), inline())
                .execute(VideoTools.STATUS, input("job_id", jobId), ToolContext.none(), paths()));

        assertTrue(status.contains("dit : « Mickaël : Le board RH. »"), status);
        assertTrue(status.contains("Transcription complète : "), status);
    }

    @Test
    void une_transcription_introuvable_est_refusee_avant_de_demarrer() throws IOException {
        deposit("demo.mp4");
        VideoTools tools = tools(VideoFixtures.frames(home, writing(0)), inline());
        ObjectNode request = input("path", ".atelier/entrees/demo.mp4");
        request.put("transcript", "absente.vtt");

        ToolOutcome outcome = tools.execute(VideoTools.IMAGES, request, ToolContext.none(), paths());

        assertFalse(outcome.ok());
        assertTrue(outcome.errorMessage().contains("Je ne trouve pas la transcription"),
                outcome.errorMessage());
    }

    // ------------------------------------------------------------------ outillage

    private Path deposit(String name) throws IOException {
        Path dir = Files.createDirectories(home.resolve(".atelier/entrees"));
        return Files.writeString(dir.resolve(name), "pas vraiment une vidéo");
    }

    private PathResolver paths() {
        return new PathResolver(home);
    }

    private static VideoTools tools(SceneFrames frames, ExecutorService pool) {
        return new VideoTools(new VideoFramesWorker(frames, pool));
    }

    /** Un faux ffmpeg qui écrit {@code count} vraies images et date chacune. */
    private static ProcessRunner writing(int count, double... times) {
        return (command, dir, timeout) -> {
            VideoFixtures.writeDistinctFrames(Path.of(command.get(command.size() - 1)).getParent(),
                    count);
            return result(times);
        };
    }

    private static ProcessRunner.ProcessResult result(double... times) {
        List<String> stderr = new ArrayList<>();
        for (int index = 0; index < times.length; index++) {
            stderr.add(VideoFixtures.showinfo(index, times[index]));
        }
        return new ProcessRunner.ProcessResult(0, List.of(), stderr, false);
    }

    private static String statusOf(VideoTools tools, String started) {
        return ok(tools.execute(VideoTools.STATUS, input("job_id", jobId(started)),
                ToolContext.none(), new PathResolver(Path.of("."))));
    }

    private static String ok(ToolOutcome outcome) {
        assertTrue(outcome.ok(), outcome.errorMessage());
        return outcome.content();
    }

    private static String jobId(String rendered) {
        return rendered.lines().filter(line -> line.startsWith("job_id : ")).findFirst()
                .orElseThrow().substring("job_id : ".length()).strip();
    }

    private static ObjectNode input(String field, String value) {
        ObjectNode node = JSON.createObjectNode();
        node.put(field, value);
        return node;
    }

    /** Exécute le travail sur place : le résultat est prêt quand l'outil rend la main. */
    private static ExecutorService inline() {
        return new AbstractExecutorService() {
            private volatile boolean shutdown;

            @Override
            public void execute(Runnable command) {
                command.run();
            }

            @Override
            public void shutdown() {
                shutdown = true;
            }

            @Override
            public List<Runnable> shutdownNow() {
                shutdown = true;
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                return shutdown;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }
        };
    }
}
