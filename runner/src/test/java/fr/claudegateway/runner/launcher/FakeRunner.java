package fr.claudegateway.runner.launcher;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Un <b>faux runner</b> pour le test d'intégration du lanceur : exécuté dans un vrai processus enfant,
 * depuis un vrai jar, il écrit ce qu'il a reçu puis agit selon sa ressource {@code fake-runner.txt}, faite
 * de paires {@code clé=valeur} :
 * <ul>
 *   <li>{@code version} — l'identifiant écrit dans le journal ;</li>
 *   <li>{@code code} — le code de sortie ;</li>
 *   <li>{@code next} — la version à écrire dans {@code next-version} avant de sortir ;</li>
 *   <li>{@code health=true} — écrire le témoin de santé (liaison établie) ;</li>
 *   <li>{@code sleep} — millisecondes d'attente avant de sortir ;</li>
 *   <li>{@code ifReport} — code de sortie si un rapport de retour arrière attend (il est alors journalisé).</li>
 * </ul>
 */
public final class FakeRunner {

    private FakeRunner() {
    }

    public static void main(String[] args) throws Exception {
        String spec;
        try (InputStream in = FakeRunner.class.getResourceAsStream("/fake-runner.txt")) {
            spec = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        Map<String, String> options = new HashMap<>();
        for (String pair : spec.split(" ")) {
            int eq = pair.indexOf('=');
            options.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        String version = options.get("version");
        LauncherHome home = new LauncherHome(Path.of(System.getenv(LauncherHome.HOME_ENV)));
        String report = Files.isRegularFile(home.reportFile()) ? Files.readString(home.reportFile()) : "";
        Path log = Path.of(System.getenv("FAKE_RUNNER_LOG"));
        String line = version + "|" + String.join(" ", args) + "|"
                + (System.getenv(JavaChild.LAUNCHER_PID_ENV) != null) + "|"
                + System.getenv("FAKE_RUNNER_INHERITED") + "|" + ProcessHandle.current().pid() + "|" + report
                + System.lineSeparator();
        Files.writeString(log, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (!report.isEmpty() && options.containsKey("ifReport")) {
            System.exit(Integer.parseInt(options.get("ifReport")));
        }
        if ("true".equals(options.get("health"))) {
            home.markConnected(version, ProcessHandle.current().pid());
        }
        if (options.containsKey("next")) {
            home.setNextVersion(options.get("next"));
        }
        if (options.containsKey("sleep")) {
            Thread.sleep(Long.parseLong(options.get("sleep")));
        }
        System.exit(Integer.parseInt(options.getOrDefault("code", "0")));
    }
}
