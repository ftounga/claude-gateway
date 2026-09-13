package fr.claudegateway.runner.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import fr.claudegateway.runner.RunnerBuild;

/**
 * Le cycle réel du lanceur (F-111 / SF-111-02, SF-111-05), sur Linux : de vrais processus {@code java}, de
 * vrais jars, un vrai code de sortie 75, un vrai témoin de santé et un vrai retour arrière.
 */
@EnabledOnOs(OS.LINUX)
class LauncherIntegrationTest {

    private static final String V0 = "0.9.0-202609010000-zzzzzzz";
    private static final String V1 = "1.0.0-202609130000-aaaaaaa";
    private static final String V2 = "1.0.0-202609140000-bbbbbbb";

    @TempDir
    Path dir;

    private final List<String> said = new ArrayList<>();

    @Test
    void lanceurEnfantSortie75NouvelEnfantConnecteMemesArgumentsMemeEnvironnement() throws Exception {
        Path home = dir.resolve("home");
        Path log = dir.resolve("log.txt");
        LauncherHome launcherHome = new LauncherHome(home);
        launcherHome.install(V0, fakeJar("v0.jar", "version=" + V0 + " code=0"));
        Path jarV1 = fakeJar("v1.jar", "version=" + V1 + " code=75 next=" + V2 + " health=true");
        launcherHome.install(V2, fakeJar("v2.jar", "version=" + V2 + " code=0 health=true sleep=1500"));

        Launcher launcher = launcher(launcherHome, jarV1, V1, log, Duration.ofSeconds(20));
        int code = launcher.run(List.of("--gateway", "https://gateway.test/api", "--root", "/tmp"));

        assertEquals(0, code, String.join("\n", said));
        List<String> lines = Files.readAllLines(log);
        assertEquals(2, lines.size(), "deux enfants : " + lines);
        String[] first = lines.get(0).split("\\|", -1);
        String[] second = lines.get(1).split("\\|", -1);
        assertEquals(V1, first[0]);
        assertEquals(V2, second[0], "le second enfant est la version écrite dans next-version");
        assertEquals(first[1], second[1], "mêmes arguments");
        assertEquals("--gateway https://gateway.test/api --root /tmp", first[1]);
        assertEquals("true", first[2], "l'enfant sait qu'il a un lanceur");
        assertEquals("hérité", first[3], "même environnement");
        assertEquals("hérité", second[3]);
        assertTrue(launcherHome.isInstalled(V1), "installation initiale dans versions/");
        assertEquals(V2, launcherHome.currentVersion().orElseThrow(), "confirmée par sa connexion");
        assertTrue(launcherHome.nextVersion().isEmpty(), "next-version consommé");
        assertTrue(launcherHome.isInstalled(V0), "deux versions précédentes conservées : V1 et V0");
    }

    @Test
    void uneVersionQuiNeSeReconnectePasEstRemplaceeParLaPrecedente() throws Exception {
        Path log = dir.resolve("log.txt");
        LauncherHome home = new LauncherHome(dir.resolve("home"));
        Path jarV1 = fakeJar("v1.jar", "version=" + V1 + " code=75 next=" + V2 + " health=true ifReport=0");
        home.install(V2, fakeJar("v2.jar", "version=" + V2 + " code=0 sleep=600000"));

        int code = launcher(home, jarV1, V1, log, Duration.ofSeconds(3)).run(List.of("--root", "/tmp"));

        assertEquals(0, code, String.join("\n", said));
        List<String> lines = Files.readAllLines(log);
        assertEquals(List.of(V1, V2, V1), lines.stream().map(line -> line.split("\\|")[0]).toList());
        String report = lines.get(2).split("\\|", -1)[5];
        assertTrue(report.contains("\"result\":\"rolled_back\"") && report.contains("\"to\":\"" + V2 + "\"")
                && report.contains("ne s'est pas reconnectée"), report);
        assertEquals(V1, home.currentVersion().orElseThrow());
        assertFalse(home.isInstalled(V2), "la version en échec est retirée : elle sera retéléchargée et revérifiée");
        long v2Pid = Long.parseLong(lines.get(1).split("\\|")[4]);
        assertFalse(ProcessHandle.of(v2Pid).map(ProcessHandle::isAlive).orElse(false), "V2 muette a été arrêtée");
    }

    @Test
    void troisPlantagesDeLaNouvelleVersionRamenentLaPrecedente() throws Exception {
        Path log = dir.resolve("log.txt");
        LauncherHome home = new LauncherHome(dir.resolve("home"));
        Path jarV1 = fakeJar("v1.jar", "version=" + V1 + " code=75 next=" + V2 + " ifReport=0");
        home.install(V2, fakeJar("v2.jar", "version=" + V2 + " code=1"));

        int code = launcher(home, jarV1, V1, log, Duration.ofSeconds(30)).run(List.of());

        assertEquals(0, code, String.join("\n", said));
        List<String> versions = Files.readAllLines(log).stream().map(line -> line.split("\\|")[0]).toList();
        assertEquals(List.of(V1, V2, V2, V2, V1), versions);
        assertTrue(Files.readAllLines(log).get(4).contains("3 plantages"), Files.readAllLines(log).get(4));
    }

    @Test
    void desMisesAJourIntrouvablesARepetitionNeTournentPasEnBoucle() throws Exception {
        Path log = dir.resolve("log.txt");
        Path jar = fakeJar("v1.jar", "version=" + V1 + " code=75 next=" + V2);
        Launcher launcher = launcher(new LauncherHome(dir.resolve("home")), jar, V1, log, Duration.ofSeconds(20));

        int code = launcher.run(List.of());

        assertEquals(75, code, String.join("\n", said));
        assertEquals(4, Files.readAllLines(log).size(), "comptées comme des plantages : 3 relances au plus");
    }

    @Test
    void unPlantageEstRelanceTroisFoisPuisLeLanceurRendLaMain() throws Exception {
        Path log = dir.resolve("log.txt");
        Path jar = fakeJar("crash.jar", "version=" + V1 + " code=1");
        Launcher launcher = launcher(new LauncherHome(dir.resolve("home")), jar, V1, log, Duration.ofSeconds(20));

        int code = launcher.run(List.of("--root", "/tmp"));

        assertEquals(1, code);
        assertEquals(4, Files.readAllLines(log).size(), "un démarrage et trois relances");
    }

    @Test
    void lArretDuLanceurArreteLEnfantSansOrphelin() throws Exception {
        Path log = dir.resolve("log.txt");
        Path jar = fakeJar("long.jar", "version=" + V1 + " code=0 sleep=600000");
        Launcher launcher = launcher(new LauncherHome(dir.resolve("home")), jar, V1, log, Duration.ofSeconds(20));

        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(() -> launcher.run(List.of()));
        long deadline = System.currentTimeMillis() + 20_000;
        while ((!Files.exists(log) || Files.readAllLines(log).isEmpty())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        long childPid = Long.parseLong(Files.readAllLines(log).get(0).split("\\|")[4]);
        assertTrue(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));

        launcher.stopFromSignal(); // exactement ce que le crochet d'arrêt exécute

        run.get(20, TimeUnit.SECONDS);
        Thread.sleep(200);
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                "aucun orphelin : l'enfant s'arrête avec le lanceur");
    }

    private Launcher launcher(LauncherHome home, Path ownJar, String embeddedId, Path log, Duration health) {
        JavaChild child = new JavaChild(JavaChild.javaExecutable(System.getProperty("java.home"),
                System.getProperty("os.name")), List.of(), FakeRunner.class.getName(),
                Map.of(JavaChild.LAUNCHER_PID_ENV, String.valueOf(ProcessHandle.current().pid()),
                        LauncherHome.HOME_ENV, home.root().toString(),
                        "FAKE_RUNNER_LOG", log.toString(),
                        "FAKE_RUNNER_INHERITED", "hérité"));
        return new Launcher(home, child, RunnerBuild.parseId(embeddedId).orElseThrow(), ownJar,
                said::add, Clock.systemUTC(), false, health);
    }

    /** Un jar contenant le faux runner et ce qu'il doit faire, plus ce dont il dépend. */
    private Path fakeJar(String name, String spec) throws Exception {
        Path jar = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
            List<String> entries = new ArrayList<>();
            for (Class<?> type : List.of(FakeRunner.class, LauncherHome.class, LauncherHome.UpdateReport.class,
                    JavaChild.class, RunnerBuild.class)) {
                entries.add(type.getName().replace('.', '/') + ".class");
            }
            for (String entry : entries) {
                zip.putNextEntry(new JarEntry(entry));
                try (InputStream in = FakeRunner.class.getClassLoader().getResourceAsStream(entry)) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
            zip.putNextEntry(new JarEntry("fake-runner.txt"));
            zip.write(spec.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }
}
