package fr.claudegateway.runner.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.PathResolver;
import fr.claudegateway.runner.ToolContext;
import fr.claudegateway.runner.ToolOutcome;
import fr.claudegateway.runner.teams.LocalToolchain;
import fr.claudegateway.runner.teams.ProcessRunner;
import fr.claudegateway.runner.teams.SceneFrames;
import fr.claudegateway.runner.teams.TeamsWorkFolder;

/**
 * <b>La chaîne avec un VRAI {@code ffmpeg}</b> (F-190 / SF-190-04).
 *
 * <p>Tous les autres tests simulent {@code ffmpeg} et fabriquent à la main la ligne
 * {@code showinfo} dont dépend l'horodatage. Celui-ci prouve ce qu'ils ne peuvent pas prouver : qu'un
 * vrai {@code ffmpeg} écrit bien ce journal, que le seuil de changement de plan voit les vrais
 * changements, que le zoom date en temps absolu, et que la planche se compose sur de vraies images.
 * Il est <b>ignoré</b> (et le dit) quand la machine de test n'a pas {@code ffmpeg}.</p>
 */
class VideoRealFfmpegTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** « case 1 · 00:00:04 · chemin » — l'instant rendu d'une case. */
    private static final Pattern CELL = Pattern.compile("case (\\d+) · (\\d{2}):(\\d{2}):(\\d{2}) · (\\S.*)");

    @TempDir
    static Path home;

    private static Path video;

    @BeforeAll
    static void uneVraieVideoEnTroisPlans() throws Exception {
        assumeTrue(onPath("ffmpeg"), "ffmpeg absent de cette machine : test ignoré");
        Path dir = Files.createDirectories(home.resolve(".atelier/entrees"));
        video = dir.resolve("trois plans.mp4");
        // Trois sources à motifs (et non des aplats : deux aplats ont la même empreinte et seraient
        // pris pour des doublons), 4 s chacune — les plans changent à 4 s et à 8 s.
        Process process = new ProcessBuilder("ffmpeg", "-hide_banner", "-nostdin", "-y",
                "-f", "lavfi", "-i", "testsrc=s=320x240:r=10:d=4",
                "-f", "lavfi", "-i", "smptebars=s=320x240:r=10:d=4",
                "-f", "lavfi", "-i", "mandelbrot=s=320x240:r=10,trim=duration=4",
                "-filter_complex", "[0][1][2]concat=n=3:v=1:a=0,format=yuv420p",
                video.toString()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0,
                "la vidéo de test n'a pas pu être fabriquée");
    }

    @Test
    void les_changements_de_plan_reels_sont_dates_par_le_vrai_journal_showinfo() throws IOException {
        VideoTools tools = tools();

        String status = run(tools, input(".atelier/entrees/trois plans.mp4"));

        List<double[]> cells = cells(status);
        assertTrue(cells.size() >= 3, "le premier écran et les deux changements de plan : " + status);
        assertTrue(cells.stream().anyMatch(cell -> cell[1] <= 1), "le premier écran est retenu : " + status);
        assertTrue(cells.stream().anyMatch(cell -> Math.abs(cell[1] - 4) <= 1), status);
        assertTrue(cells.stream().anyMatch(cell -> Math.abs(cell[1] - 8) <= 1), status);
        Path sheet = home.resolve(line(status, "Planche 1 : ").strip());
        assertEquals(1536, ImageIO.read(sheet.toFile()).getWidth(), "planche composée sur de vraies images");
        for (String path : paths(status)) {
            assertTrue(ImageIO.read(home.resolve(path).toFile()) != null, "image lisible : " + path);
        }
    }

    @Test
    void le_zoom_date_en_temps_absolu() throws IOException {
        VideoTools tools = tools();
        ObjectNode request = input(".atelier/entrees/trois plans.mp4");
        request.put("mode", "intervalle").put("from", 2).put("to", 11).put("every_seconds", 4);

        String status = run(tools, request);

        List<double[]> cells = cells(status);
        assertTrue(cells.size() >= 2, status);
        double first = cells.get(0)[1];
        assertTrue(first >= 2 && first <= 3, "la première image est datée depuis le début de la "
                + "vidéo, pas depuis -ss : " + status);
        for (double[] cell : cells) {
            assertTrue(cell[1] >= 2 && cell[1] <= 11, "dans l'intervalle : " + status);
        }
    }

    // ------------------------------------------------------------------ outillage

    private static VideoTools tools() {
        ProcessRunner processes = ProcessRunner.real();
        LocalToolchain toolchain = new LocalToolchain(new TeamsWorkFolder(home), processes, message -> { });
        return new VideoTools(new VideoFramesWorker(new SceneFrames(toolchain, processes)));
    }

    /** Lance puis attend la fin (le travail tourne en tâche de fond, comme sur le poste). */
    private static String run(VideoTools tools, ObjectNode request) {
        PathResolver paths = new PathResolver(home);
        ToolOutcome started = tools.execute(VideoTools.IMAGES, request, ToolContext.none(), paths);
        assertTrue(started.ok(), started.errorMessage());
        String jobId = line(started.content(), "job_id : ").strip();
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            String status = tools.execute(VideoTools.STATUS, JSON.createObjectNode().put("job_id", jobId),
                    ToolContext.none(), paths).content();
            if (status.contains("étape : Terminé") || status.contains("étape : Échec")) {
                assertTrue(status.contains("étape : Terminé"), status);
                return status;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("extraction non terminée en 2 minutes");
    }

    private static List<double[]> cells(String status) {
        List<double[]> cells = new ArrayList<>();
        for (String line : status.lines().toList()) {
            Matcher matcher = CELL.matcher(line.strip());
            if (matcher.matches()) {
                cells.add(new double[] {Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)) * 3600d + Integer.parseInt(matcher.group(3)) * 60d
                                + Integer.parseInt(matcher.group(4))});
            }
        }
        return cells;
    }

    private static List<String> paths(String status) {
        List<String> paths = new ArrayList<>();
        for (String line : status.lines().toList()) {
            Matcher matcher = CELL.matcher(line.strip());
            if (matcher.matches()) {
                paths.add(matcher.group(5).strip());
            }
        }
        return paths;
    }

    private static String line(String text, String prefix) {
        return text.lines().filter(line -> line.startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("« " + prefix + " » absent de :\n" + text))
                .substring(prefix.length());
    }

    private static ObjectNode input(String path) {
        return JSON.createObjectNode().put("path", path);
    }

    private static boolean onPath(String name) {
        try {
            Process process = new ProcessBuilder(name, "-version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
