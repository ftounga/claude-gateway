package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Élection de l'interpréteur (F-38 / SF-38-27).
 *
 * <p>Les cas Windows sont joués <b>depuis n'importe quelle plateforme</b> : l'élection reçoit son
 * système, ses variables d'environnement et son prédicat « ce chemin est exécutable ». C'est ce qui
 * rend vérifiable, sur une machine d'intégration continue sous Linux, un comportement qui ne se
 * produit que sous Windows — et c'est précisément le comportement que personne n'avait vu.</p>
 */
class ShellElectionTest {

    @TempDir
    Path disk;

    // ----------------------------------------------------------------- Unix

    @Test
    void surUnixElitLeShellPosixHistorique() {
        ShellElection election = ShellElection.elect(OperatingSystem.LINUX, Map.of(), path -> true);

        assertEquals(ShellElection.Kind.POSIX, election.kind());
        assertEquals(List.of("/bin/sh", "-c", "ls -la"), election.commandLine("ls -la"),
                "Le comportement d'avant SF-38-27 doit être strictement conservé sur Unix");
    }

    @Test
    void surUnixSansBinShCherecheDansLePath() throws IOException {
        Path bin = Files.createDirectories(disk.resolve("usr-local-bin"));
        Path sh = executable(bin.resolve("sh"));

        ShellElection election = ShellElection.elect(OperatingSystem.LINUX,
                Map.of("PATH", bin.toString()), known(sh));

        assertEquals(ShellElection.Kind.POSIX, election.kind());
        assertEquals(sh.toString(), election.commandLine("echo x").get(0));
    }

    @Test
    void surUnSystemeInconnuResteEnPosix() {
        ShellElection election = ShellElection.elect(OperatingSystem.OTHER, Map.of(), path -> false);

        assertEquals(ShellElection.Kind.POSIX, election.kind());
        assertEquals(List.of("/bin/sh", "-c", "echo x"), election.commandLine("echo x"));
    }

    // -------------------------------------------------------------- Windows

    @Test
    void surWindowsElitGitBashAvantToutLeReste() throws IOException {
        Path programFiles = Files.createDirectories(disk.resolve("Program Files"));
        Path gitBash = executable(
                Files.createDirectories(programFiles.resolve("Git").resolve("bin")).resolve("bash.exe"));
        Path system32 = Files.createDirectories(disk.resolve("System32"));
        Path powerShell = executable(system32.resolve("powershell.exe"));

        ShellElection election = ShellElection.elect(OperatingSystem.WINDOWS,
                windows(Map.of("ProgramFiles", programFiles.toString(),
                        "PATH", system32.toString())),
                known(gitBash, powerShell));

        assertEquals(ShellElection.Kind.POSIX, election.kind());
        assertEquals(List.of(gitBash.toString(), "-c", "grep -n foo ."),
                election.commandLine("grep -n foo ."));
    }

    @Test
    void surWindowsElitGitBashInstallePourLUtilisateurSeul() throws IOException {
        Path localAppData = Files.createDirectories(disk.resolve("AppData").resolve("Local"));
        Path gitBash = executable(Files.createDirectories(
                localAppData.resolve("Programs").resolve("Git").resolve("bin")).resolve("bash.exe"));

        ShellElection election = ShellElection.elect(OperatingSystem.WINDOWS,
                windows(Map.of("LOCALAPPDATA", localAppData.toString())), known(gitBash));

        assertEquals(ShellElection.Kind.POSIX, election.kind());
        assertEquals(gitBash.toString(), election.commandLine("ls").get(0));
    }

    @Test
    void surWindowsSansBashElitPowerShellAvantCmd() throws IOException {
        Path tools = Files.createDirectories(disk.resolve("tools"));
        Path pwsh = executable(tools.resolve("pwsh.exe"));

        ShellElection election = ShellElection.elect(OperatingSystem.WINDOWS,
                windows(Map.of("PATH", tools.toString())), known(pwsh));

        assertEquals(ShellElection.Kind.POWERSHELL, election.kind());
        List<String> line = election.commandLine("Get-ChildItem");
        assertEquals(pwsh.toString(), line.get(0));
        assertEquals(List.of("-NoProfile", "-NonInteractive", "-EncodedCommand"),
                line.subList(1, 4));
    }

    @Test
    void surWindowsTrouvePowerShellAuCheminSystemeQuandLePathEstMuet() throws IOException {
        Path systemRoot = Files.createDirectories(disk.resolve("Windows"));
        Path powerShell = executable(Files.createDirectories(systemRoot.resolve("System32")
                .resolve("WindowsPowerShell").resolve("v1.0")).resolve("powershell.exe"));

        ShellElection election = ShellElection.elect(OperatingSystem.WINDOWS,
                windows(Map.of("SystemRoot", systemRoot.toString())), known(powerShell));

        assertEquals(ShellElection.Kind.POWERSHELL, election.kind());
        assertEquals(powerShell.toString(), election.commandLine("Get-ChildItem").get(0));
    }

    @Test
    void surWindowsSansRienRetombeSurCmd() {
        ShellElection election =
                ShellElection.elect(OperatingSystem.WINDOWS, windows(Map.of()), path -> false);

        assertEquals(ShellElection.Kind.CMD, election.kind());
        assertEquals(List.of("cmd.exe", "/c", "dir"), election.commandLine("dir"));
    }

    @Test
    void surWindowsUtiliseComSpecQuandIlEstLa() throws IOException {
        Path system32 = Files.createDirectories(disk.resolve("System32"));
        Path cmd = executable(system32.resolve("cmd.exe"));

        ShellElection election = ShellElection.elect(OperatingSystem.WINDOWS,
                windows(Map.of("ComSpec", cmd.toString())), known(cmd));

        assertEquals(ShellElection.Kind.CMD, election.kind());
        assertEquals(cmd.toString(), election.commandLine("dir").get(0));
    }

    // ------------------------------------------------------- le piège du WSL

    @Test
    void nElitJamaisLeBashDeWsl() throws IOException {
        // System32\bash.exe existe sur presque tout Windows 10+ : c'est le lanceur WSL, qui ouvre
        // une distribution Linux dont le système de fichiers n'est PAS celui du projet.
        Path system32 = Files.createDirectories(disk.resolve("System32"));
        Path wslLauncher = executable(system32.resolve("bash.exe"));
        Path cmd = executable(system32.resolve("cmd.exe"));

        ShellElection election = ShellElection.elect(OperatingSystem.WINDOWS,
                windows(Map.of("PATH", system32.toString(), "ComSpec", cmd.toString())),
                known(wslLauncher, cmd));

        assertNotEquals(ShellElection.Kind.POSIX, election.kind(),
                "Le lanceur WSL ne doit jamais être élu comme interpréteur du poste");
        assertEquals(ShellElection.Kind.CMD, election.kind());
    }

    @Test
    void reconnaitLesDossiersLanceursDeWsl() throws IOException {
        assertTrue(ShellElection.isWslLauncherDirectory(
                Files.createDirectories(disk.resolve("SysWOW64"))));
        assertTrue(ShellElection.isWslLauncherDirectory(
                Files.createDirectories(disk.resolve("WindowsApps"))));
        assertFalse(ShellElection.isWslLauncherDirectory(
                Files.createDirectories(disk.resolve("Git").resolve("bin"))));
    }

    @Test
    void ignoreUnCandidatNonExecutable() throws IOException {
        Path programFiles = Files.createDirectories(disk.resolve("Program Files"));
        Path gitBash = Files.createFile(
                Files.createDirectories(programFiles.resolve("Git").resolve("bin")).resolve("bash.exe"));

        // Le fichier existe mais n'est pas exécutable : il n'est pas un candidat.
        ShellElection election = ShellElection.elect(OperatingSystem.WINDOWS,
                windows(Map.of("ProgramFiles", programFiles.toString())), path -> false);

        assertEquals(ShellElection.Kind.CMD, election.kind());
        assertTrue(Files.exists(gitBash));
    }

    // ------------------------------------------------------ ce qui est déclaré

    @Test
    void neDeclareQueLeGenreJamaisLeChemin() throws IOException {
        Path programFiles = Files.createDirectories(disk.resolve("Program Files"));
        Path gitBash = executable(
                Files.createDirectories(programFiles.resolve("Git").resolve("bin")).resolve("bash.exe"));

        ShellElection election = ShellElection.elect(OperatingSystem.WINDOWS,
                windows(Map.of("ProgramFiles", programFiles.toString())), known(gitBash));

        assertEquals("posix", election.declaredName());
        assertFalse(election.declaredName().contains(disk.toString()),
                "Le chemin de la machine ne doit jamais remonter à la gateway");
    }

    @Test
    void chaqueGenreADeclareUneValeurDeLaListeBlanche() {
        assertEquals("posix", ShellElection.Kind.POSIX.declared());
        assertEquals("powershell", ShellElection.Kind.POWERSHELL.declared());
        assertEquals("cmd", ShellElection.Kind.CMD.declared());
    }

    // ------------------------------------------------------------ PowerShell

    @Test
    void encodeLaCommandePowerShellEnUtf16EtPropageLeCodeDeSortie() {
        String encoded = ShellElection.powerShellArguments("Select-String -Pattern \"class Foo\"");

        String script = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_16LE);
        assertTrue(script.contains("Select-String -Pattern \"class Foo\""),
                "Les guillemets de la commande traversent l'encodage sans déformation");
        assertTrue(script.contains("exit $LASTEXITCODE"),
                "PowerShell rendrait 0 même après une commande native en échec sans cette ligne");
    }

    // ---------------------------------------------------------------- outils

    /** Environnement Windows minimal, complété par les entrées du cas. */
    private static Map<String, String> windows(Map<String, String> entries) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SystemRoot", "C:\\Windows");
        env.putAll(entries);
        return env;
    }

    /** Prédicat « exécutable » restreint à des chemins connus — le système de fichiers, simulé. */
    private static java.util.function.Predicate<Path> known(Path... executables) {
        List<Path> allowed = List.of(executables);
        return allowed::contains;
    }

    private static Path executable(Path path) throws IOException {
        Files.createFile(path);
        path.toFile().setExecutable(true);
        return path;
    }
}
