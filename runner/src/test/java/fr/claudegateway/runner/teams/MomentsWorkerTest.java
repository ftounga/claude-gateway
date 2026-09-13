package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>Le travail long, la reprise, et ce qui remonte</b> (F-90 / SF-90-03).
 *
 * <p>Ce qui est prouvé ici : le travail tourne <b>hors</b> du fil de l'appel, il <b>dit</b> ses
 * étapes, il <b>reprend</b> au lieu de recommencer, chaque échec porte son <b>remède</b>, et une
 * remontée refusée est <b>comptée</b> sans faire tomber le compte rendu — sauf quand elles le sont
 * toutes.</p>
 *
 * <p><b>Limite écrite et non maquillée</b> : {@code ffmpeg} est de papier, la gateway aussi, et nous
 * n'avons aucun compte Teams de test.</p>
 */
class MomentsWorkerTest {

    private static final Instant DEBUT = Instant.parse("2026-09-12T14:00:00Z");

    @TempDir
    Path home;

    private final List<String> said = new ArrayList<>();

    @Test
    void le_travail_dit_ses_etapes_et_rend_ses_moments() throws Exception {
        Recorder uploads = new Recorder();
        MomentsJob job = runToEnd(worker(twoScenes(), uploads), request(false));

        assertEquals(MomentsJob.Phase.TERMINE, job.phase());
        assertEquals(2, job.moments().size());
        assertEquals(2, job.uploaded());
        assertEquals(0, job.uploadRefused());
        // Les étapes se voient travailler : c'est le §5.5 du cadrage.
        assertTrue(said.stream().anyMatch(line -> line.contains("ffmpeg")));
        assertTrue(said.stream().anyMatch(line -> line.contains("changements de plan")));
        assertTrue(said.stream().anyMatch(line -> line.contains("à côté de la phrase")));
        assertTrue(said.stream().anyMatch(line -> line.contains("restent sur cette machine")));
    }

    @Test
    void le_resultat_ne_porte_que_des_identifiants_dimage_jamais_des_octets() throws Exception {
        Recorder uploads = new Recorder();
        MomentsJob job = runToEnd(worker(twoScenes(), uploads), request(false));

        for (MomentsJob.Moment moment : job.moments()) {
            assertFalse(moment.imageId().isBlank());
            assertTrue(moment.imageId().length() < 64, "un identifiant, pas des octets");
        }
        // Ce sont bien les fichiers LOCAUX qui ont été lus, et eux seuls sont montés.
        assertEquals(2, uploads.sent.size());
    }

    @Test
    void redemander_le_meme_enregistrement_reprend_au_lieu_de_recommencer() throws Exception {
        MomentsWorker worker = worker(twoScenes(), new Recorder());
        MomentsWorker.Request request = request(false);
        runToEnd(worker, request);
        said.clear();

        MomentsJob again = worker.startOrResume(request, said::add);

        assertEquals(MomentsJob.Phase.TERMINE, again.phase());
        assertTrue(said.stream().anyMatch(line -> line.contains("déjà été fait")),
                "la reprise SE DIT : sans cela, l'utilisateur croirait avoir relancé");
    }

    @Test
    void lidentifiant_est_tire_du_fichier_donc_retrouvable_sans_etre_note() throws Exception {
        Path video = video("reunion.mp4");
        String first = MomentsJobStore.idFor(video);

        assertEquals(first, MomentsJobStore.idFor(video), "stable pour un fichier inchangé");
        Files.writeString(video, "un enregistrement différent, plus long");
        assertNotEquals(first, MomentsJobStore.idFor(video),
                "un fichier modifié n'est plus la même réunion");
    }

    @Test
    void un_travail_echoue_porte_son_remede_et_ne_se_relance_pas_tout_seul() throws Exception {
        // ffmpeg introuvable et non rapatriable : le travail échoue EN DISANT quoi faire.
        MomentsWorker worker = worker(new FakeProcesses(), new Recorder(), false);
        MomentsJob failed = runToEnd(worker, request(false));

        assertEquals(MomentsJob.Phase.ECHOUE, failed.phase());
        assertTrue(failed.remedy().contains("gestionnaire de paquets")
                || failed.remedy().contains("apt"), "un échec nu n'aide personne");
        assertTrue(failed.describe().contains("n'a pas abouti"));

        said.clear();
        MomentsJob resumed = worker.startOrResume(request(false), said::add);
        assertEquals(MomentsJob.Phase.ECHOUE, resumed.phase(),
                "sans restart, on rend l'échec — on ne relance pas en boucle");
    }

    @Test
    void restart_relance_explicitement_un_travail_deja_fait() throws Exception {
        Recorder uploads = new Recorder();
        MomentsWorker worker = worker(twoScenes(), uploads);
        runToEnd(worker, request(false));
        int montees = uploads.sent.size();

        MomentsJob relance = runToEnd(worker, request(true));

        assertEquals(MomentsJob.Phase.TERMINE, relance.phase());
        assertTrue(uploads.sent.size() > montees, "restart refait réellement le travail");
        assertTrue(said.stream().noneMatch(line -> line.contains("déjà été fait")),
                "un redémarrage explicite ne se présente pas comme une reprise");
    }

    @Test
    void sans_origine_du_temps_le_travail_echoue_en_nommant_les_deux_remedes() throws Exception {
        MomentsWorker worker = worker(twoScenes(), new Recorder());
        MomentsWorker.Request sansOrigine = new MomentsWorker.Request(video("reunion.mp4"),
                "w-1", cues(), MomentTimeline.unknown(), 0d, "Comité", false);

        MomentsJob job = runToEnd(worker, sansOrigine);

        assertEquals(MomentsJob.Phase.ECHOUE, job.phase());
        assertTrue(job.remedy().contains("video_started_at"));
        assertTrue(job.remedy().contains("meeting_id"));
    }

    @Test
    void une_image_refusee_est_comptee_et_le_travail_continue() throws Exception {
        Recorder uploads = new Recorder().refusingEveryOther();
        MomentsJob job = runToEnd(worker(twoScenes(), uploads), request(false));

        assertEquals(MomentsJob.Phase.TERMINE, job.phase());
        assertEquals(1, job.uploaded());
        assertEquals(1, job.uploadRefused());
        assertEquals(2, job.moments().size(), "le moment reste, sans capture");
        assertTrue(job.moments().stream().anyMatch(moment -> moment.imageId().isEmpty()));
        assertTrue(job.gaps().stream()
                .anyMatch(gap -> gap.kind() == TeamsGapKind.UPLOAD_REFUSED));
        assertTrue(job.describe().contains("sans capture"));
    }

    @Test
    void toutes_les_images_refusees_font_echouer_le_travail() throws Exception {
        Recorder uploads = new Recorder().refusingAll();

        MomentsJob job = runToEnd(worker(twoScenes(), uploads), request(false));

        assertEquals(MomentsJob.Phase.ECHOUE, job.phase());
        assertTrue(job.failure().contains("transcription découpée")
                        || job.remedy().contains("transcription découpée"),
                "un compte rendu de moments sans aucune image n'est pas un compte rendu de moments");
    }

    @Test
    void letat_survit_au_redemarrage_du_runner() throws Exception {
        MomentsWorker worker = worker(twoScenes(), new Recorder());
        MomentsWorker.Request request = request(false);
        MomentsJob job = runToEnd(worker, request);

        // Un store neuf : c'est ce que verrait un runner relancé. La phase TERMINE est posée en
        // mémoire juste avant l'écriture sur disque : on attend que le fichier la porte, sans quoi
        // la relecture perd la course sur une machine chargée.
        MomentsJobStore reread = new MomentsJobStore(new TeamsWorkFolder(home));
        MomentsJob relu = reread.find(job.id()).orElseThrow();
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!relu.isOver() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
            relu = reread.find(job.id()).orElseThrow();
        }

        assertEquals(MomentsJob.Phase.TERMINE, relu.phase());
        assertEquals(job.moments().size(), relu.moments().size());
        assertEquals(job.moments().get(0).imageId(), relu.moments().get(0).imageId());
    }

    @Test
    void une_etape_relue_mais_inconnue_ne_se_fait_pas_passer_pour_vivante() {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        MomentsJob relu = MomentsJob.fromJson(
                mapper.createObjectNode().put("id", "abc").put("phase", "N_IMPORTE_QUOI"));

        assertEquals(MomentsJob.Phase.ECHOUE, relu.phase());
    }

    // ------------------------------------------------------------------ montage de papier

    private MomentsWorker worker(FakeProcesses ffmpeg, Recorder uploads) throws IOException {
        return worker(ffmpeg, uploads, true);
    }

    private MomentsWorker worker(FakeProcesses ffmpeg, Recorder uploads, boolean toolchainReady)
            throws IOException {
        TeamsWorkFolder folder = new TeamsWorkFolder(home);
        Path binary = home.resolve("ffmpeg");
        if (toolchainReady && !Files.exists(binary)) {
            Files.createFile(binary);
            binary.toFile().setExecutable(true);
        }
        LocalToolchain toolchain = new LocalToolchain(folder, OperatingSystem.LINUX,
                (command, dir, timeout) -> FakeProcesses.version("ffmpeg"),
                (url, into) -> {
                    throw new IOException("poste hors ligne");
                },
                message -> { },
                name -> toolchainReady ? binary : null);
        return new MomentsWorker(new MomentsJobStore(folder), new SceneFrames(toolchain, ffmpeg),
                uploads, Executors.newSingleThreadExecutor());
    }

    /** Démarre puis attend : le travail tourne HORS du fil de l'appel, il faut donc l'attendre. */
    private MomentsJob runToEnd(MomentsWorker worker, MomentsWorker.Request request)
            throws Exception {
        MomentsJob job = worker.startOrResume(request, said::add);
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!job.isOver() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertTrue(job.isOver(), "le travail n'a pas abouti dans le temps imparti");
        return job;
    }

    private MomentsWorker.Request request(boolean restart) throws IOException {
        return new MomentsWorker.Request(video("reunion.mp4"), "w-1", cues(),
                MomentTimeline.given(DEBUT), 0d, "Comité de migration", restart);
    }

    private Path video(String name) throws IOException {
        Path video = home.resolve(name);
        if (!Files.exists(video)) {
            Files.writeString(video, "un enregistrement de papier");
        }
        return video;
    }

    private static List<TeamsTranscriptCue> cues() {
        return List.of(
                new TeamsTranscriptCue(DEBUT.plusSeconds(15), 4000, "p", "Paul",
                        "voici le planning de migration"),
                new TeamsTranscriptCue(DEBUT.plusSeconds(310), 4000, "l", "Léa",
                        "on décale au T3"));
    }

    /**
     * Un {@code ffmpeg} qui écrit deux images franchement différentes et le journal
     * {@code showinfo} qui va avec.
     */
    private FakeProcesses twoScenes() {
        return new FakeProcesses().answering(command -> {
            Path dir = outputDirOf(command);
            try {
                Files.createDirectories(dir);
                ImageIO.write(gradient(true), "jpg", dir.resolve("frame-00001.jpg").toFile());
                ImageIO.write(gradient(false), "jpg", dir.resolve("frame-00002.jpg").toFile());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return new ProcessRunner.ProcessResult(0, List.of(),
                    List.of(FakeProcesses.showinfo(0, 10), FakeProcesses.showinfo(1, 300)), false);
        });
    }

    /** Le dossier de sortie est le dernier argument, sous la forme {@code …/frame-%05d.jpg}. */
    private static Path outputDirOf(List<String> command) {
        return Path.of(command.get(command.size() - 1)).getParent();
    }

    private static BufferedImage gradient(boolean rising) {
        BufferedImage image = new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 160; x++) {
            int level = Math.min(255, x * 255 / 159);
            int gray = rising ? level : 255 - level;
            int rgb = new Color(gray, gray, gray).getRGB();
            for (int y = 0; y < 90; y++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    /** Une gateway de papier : elle compte ce qui monte, et sait refuser. */
    private static final class Recorder implements MomentUploader {

        final List<Path> sent = new ArrayList<>();
        private boolean refuseAll;
        private boolean refuseEveryOther;
        private int seen;

        Recorder refusingAll() {
            this.refuseAll = true;
            return this;
        }

        Recorder refusingEveryOther() {
            this.refuseEveryOther = true;
            return this;
        }

        @Override
        public String upload(String workspaceId, Path image) throws IOException {
            seen++;
            if (refuseAll || (refuseEveryOther && seen % 2 == 0)) {
                throw new RefusedException("refusée par la gateway de papier");
            }
            sent.add(image);
            return "img" + seen;
        }
    }
}
