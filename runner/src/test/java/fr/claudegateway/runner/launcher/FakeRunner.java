package fr.claudegateway.runner.launcher;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Un <b>faux runner</b> pour le test d'intégration du lanceur : exécuté dans un vrai processus enfant,
 * depuis un vrai jar, il écrit ce qu'il a reçu puis sort avec le code que dit sa ressource
 * {@code fake-runner.txt} ({@code <version> <code> [next-version] [sleep]}).
 */
public final class FakeRunner {

    private FakeRunner() {
    }

    public static void main(String[] args) throws Exception {
        String spec;
        try (InputStream in = FakeRunner.class.getResourceAsStream("/fake-runner.txt")) {
            spec = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        String[] parts = spec.split(" ");
        String version = parts[0];
        int code = Integer.parseInt(parts[1]);
        Path log = Path.of(System.getenv("FAKE_RUNNER_LOG"));
        String line = version + "|" + String.join(" ", args) + "|"
                + (System.getenv(JavaChild.LAUNCHER_PID_ENV) != null) + "|"
                + System.getenv("FAKE_RUNNER_INHERITED") + "|" + ProcessHandle.current().pid()
                + System.lineSeparator();
        Files.writeString(log, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        if (parts.length > 2 && !parts[2].equals("-")) {
            new LauncherHome(Path.of(System.getenv(LauncherHome.HOME_ENV))).setNextVersion(parts[2]);
        }
        if (parts.length > 3) {
            Thread.sleep(Long.parseLong(parts[3]));
        }
        System.exit(code);
    }
}
