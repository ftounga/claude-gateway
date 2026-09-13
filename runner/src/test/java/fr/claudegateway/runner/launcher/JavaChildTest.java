package fr.claudegateway.runner.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** F-111 / SF-111-02 — la ligne de commande du vrai runner, sur les trois systèmes. */
class JavaChildTest {

    @TempDir
    Path dir;

    @Test
    void lEnfantEstLeRunnerDeLaVersionAvecLesMemesArguments() {
        JavaChild child = new JavaChild(Path.of("/opt/jdk/bin/java"), List.of("-Dhttps.proxyHost=proxy"),
                JavaChild.RUNNER_MAIN, Map.of());

        List<String> command = child.command(Path.of("/home/u/.claude-runner/versions/1.0.0/runner.jar"),
                List.of("--gateway", "https://g/api", "--root", "C:\\dev"));

        assertEquals(List.of("/opt/jdk/bin/java", "-Dhttps.proxyHost=proxy", "-cp",
                "/home/u/.claude-runner/versions/1.0.0/runner.jar", "fr.claudegateway.runner.RunnerMain",
                "--gateway", "https://g/api", "--root", "C:\\dev"), command);
    }

    @Test
    void lExecutableJavaEstCeluiDeLaJvmDuLanceur() throws Exception {
        Path unixHome = dir.resolve("jdk");
        Files.createDirectories(unixHome.resolve("bin"));
        Files.writeString(unixHome.resolve("bin").resolve("java"), "");
        Path windowsHome = dir.resolve("runtime");
        Files.createDirectories(windowsHome.resolve("bin"));
        Files.writeString(windowsHome.resolve("bin").resolve("java.exe"), "");

        assertEquals(unixHome.resolve("bin").resolve("java"),
                JavaChild.javaExecutable(unixHome.toString(), "Linux"));
        assertEquals(unixHome.resolve("bin").resolve("java"),
                JavaChild.javaExecutable(unixHome.toString(), "Mac OS X"));
        assertEquals(windowsHome.resolve("bin").resolve("java.exe"),
                JavaChild.javaExecutable(windowsHome.toString(), "Windows 11"),
                "le paquet autonome Windows lance son propre runtime\\bin\\java.exe");
    }

    @Test
    void seulesLesOptionsDeJvmQuiDecriventUnComportementSontTransmises() {
        assertEquals(List.of("-Dhttps.proxyHost=proxy", "-Xmx512m", "-javaagent:agent.jar",
                        "--add-opens=java.base/java.lang=ALL-UNNAMED"),
                JavaChild.jvmOptionsOf(List.of("-Dhttps.proxyHost=proxy", "-Xmx512m", "-verbose:gc",
                        "-javaagent:agent.jar", "--add-opens=java.base/java.lang=ALL-UNNAMED", "-jar",
                        "claude-runner.jar", "-Dpas.une.option.jvm=1", "--gateway", "https://g")));
        assertEquals(List.of(), JavaChild.jvmOptionsOf(List.of("-jar", "claude-runner.jar")));
        assertEquals(List.of("-Dx=1"), JavaChild.jvmOptionsOf(List.of("-Dx=1", "-cp", "a.jar", "Main")));
    }

    @Test
    void lesOptionsDeCeLanceurSontLisibles() {
        // Sous Linux (où la suite tourne), la ligne de commande est lisible : aucune exception, une liste.
        assertTrue(Optional.ofNullable(JavaChild.currentJvmOptions()).isPresent());
    }
}
