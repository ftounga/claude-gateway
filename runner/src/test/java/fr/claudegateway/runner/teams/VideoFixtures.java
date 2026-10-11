package fr.claudegateway.runner.teams;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;

import fr.claudegateway.runner.OperatingSystem;

/**
 * Ce dont les tests de F-190 (paquet {@code video}) ont besoin du paquet {@code teams} : un
 * {@link SceneFrames} branché sur un faux {@code ffmpeg}, de vraies images distinctes, et la ligne
 * {@code showinfo} fabriquée de {@link FakeProcesses}.
 */
public final class VideoFixtures {

    private VideoFixtures() {
    }

    /** Un extracteur dont {@code ffmpeg} est déjà « installé » sous {@code home}. */
    public static SceneFrames frames(Path home, ProcessRunner processes) throws IOException {
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

    /** Un extracteur dont {@code ffmpeg} n'est ni présent ni rapatriable. */
    public static SceneFrames withoutFfmpeg(Path home, ProcessRunner processes) {
        LocalToolchain toolchain = new LocalToolchain(new TeamsWorkFolder(home),
                OperatingSystem.LINUX,
                (command, dir, timeout) -> new ProcessRunner.ProcessResult(1, List.of(), List.of(),
                        false),
                (url, into) -> {
                    throw new IOException("réseau coupé");
                }, message -> { }, name -> null);
        return new SceneFrames(toolchain, processes);
    }

    public static String showinfo(int index, double ptsTime) {
        return FakeProcesses.showinfo(index, ptsTime);
    }

    /** De vraies images JPEG, chacune différente (bruit d'une graine propre) : aucune n'est un doublon. */
    public static void writeDistinctFrames(Path dir, int count) {
        try {
            Files.createDirectories(dir);
            for (int index = 1; index <= count; index++) {
                BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
                Random random = new Random(index * 7919L);
                for (int y = 0; y < 48; y++) {
                    for (int x = 0; x < 64; x++) {
                        int grey = random.nextInt(256);
                        image.setRGB(x, y, (grey << 16) | (grey << 8) | grey);
                    }
                }
                ImageIO.write(image, "jpg",
                        dir.resolve(String.format("frame-%05d.jpg", index)).toFile());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
