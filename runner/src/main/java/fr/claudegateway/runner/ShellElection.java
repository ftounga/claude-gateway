package fr.claudegateway.runner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Élection de l'interpréteur sous lequel le runner exécutera les commandes (F-38 / SF-38-27).
 *
 * <p>Jusqu'ici le choix tenait en une ligne : {@code cmd.exe} dès que {@code os.name} contenait
 * « win », {@code /bin/sh} sinon. Or la consigne système envoyée au modèle en cible {@code RUNNER}
 * dicte {@code ls}, {@code find} et {@code grep -n} — et, sur cette cible, les outils
 * {@code list_files}/{@code search_files} ne sont même pas déclarés : {@code bash} est le
 * <b>seul</b> moyen d'explorer. Sur un poste Windows, chaque exploration revenait donc en
 * « n'est pas reconnu en tant que commande interne ou externe ».</p>
 *
 * <p>L'ordre d'élection suit ce que le modèle sait faire, pas ce que le système impose :</p>
 * <ol>
 *   <li><b>bash POSIX</b> — {@code /bin/sh} sur Unix ; sous Windows, Git Bash, que tout poste de
 *       développement possède avec Git pour Windows ;</li>
 *   <li><b>PowerShell</b> — {@code pwsh}, puis {@code powershell.exe} ;</li>
 *   <li><b>{@code cmd.exe}</b> — le repli garanti : jamais un échec de démarrage.</li>
 * </ol>
 *
 * <p><b>Le piège écarté (D3)</b> : Windows 10+ pose un {@code bash.exe} dans {@code System32}. Ce
 * n'est pas un shell du poste, c'est le <b>lanceur WSL</b> — il ouvre une distribution Linux dont le
 * système de fichiers n'est pas celui du projet, et le {@code cwd} confiné par le {@link PathGuard}
 * n'y existe pas. Un candidat plausible qui casse tout vaut moins que pas de candidat du tout : il
 * est exclu de l'élection.</p>
 *
 * <p>L'élection ne lève jamais. Une machine dont on n'arrive pas à sonder le système de fichiers
 * retombe sur le comportement d'avant cette subfeature.</p>
 */
public final class ShellElection {

    /**
     * Genre d'interpréteur, tel qu'il est <b>déclaré à la gateway</b> dans la trame {@code ready}.
     * Seul le genre remonte — jamais le chemin, qui est une information sur la machine.
     */
    public enum Kind {

        POSIX("posix", "bash POSIX"),
        POWERSHELL("powershell", "PowerShell"),
        CMD("cmd", "cmd.exe");

        private final String declared;
        private final String label;

        Kind(String declared, String label) {
            this.declared = declared;
            this.label = label;
        }

        /** Valeur du champ {@code shell} de la trame {@code ready}. */
        public String declared() {
            return declared;
        }

        /** Nom lisible, pour la console du runner. */
        public String label() {
            return label;
        }
    }

    /** Dossiers Windows dont un {@code bash.exe} est le lanceur WSL, pas un shell (D3). */
    private static final List<String> WSL_LAUNCHER_DIRECTORIES =
            List.of("system32", "syswow64", "sysnative", "windowsapps");

    private final Kind kind;
    private final String executable;
    private final List<String> options;

    private ShellElection(Kind kind, String executable, List<String> options) {
        this.kind = kind;
        this.executable = executable;
        this.options = List.copyOf(options);
    }

    /** Genre élu — ce que la gateway apprend. */
    public Kind kind() {
        return kind;
    }

    /** Valeur déclarée dans la trame {@code ready}. */
    public String declaredName() {
        return kind.declared();
    }

    /** Ligne d'annonce sur la console du runner : le genre <b>et</b> le binaire réellement retenu. */
    public String description() {
        return kind.label() + " — " + executable;
    }

    /**
     * Ligne de commande passée à {@link ProcessBuilder} pour exécuter {@code command}, sans découpage
     * maison des arguments : c'est l'interpréteur qui découpe, comme un humain dans son terminal.
     *
     * <p>PowerShell fait exception et reçoit la commande <b>encodée</b> (voir
     * {@link #powerShellArguments(String)}) : c'est le seul moyen sûr de lui transmettre une commande
     * qui contient des guillemets.</p>
     */
    public List<String> commandLine(String command) {
        List<String> line = new ArrayList<>(options.size() + 2);
        line.add(executable);
        line.addAll(options);
        if (kind == Kind.POWERSHELL) {
            line.add(powerShellArguments(command));
        } else {
            line.add(command);
        }
        return List.copyOf(line);
    }

    // ---------------------------------------------------------------- élection

    /** Élit l'interpréteur de cette machine. Ne lève jamais. */
    public static ShellElection elect() {
        return elect(OperatingSystem.current(), System.getenv(), ShellElection::isExecutable);
    }

    /**
     * Élection sur un environnement donné — le point testable, sur toute plateforme.
     *
     * @param os         système d'exploitation courant
     * @param env        variables d'environnement ({@code PATH}, {@code ProgramFiles}, {@code ComSpec}…)
     * @param executable prédicat « ce chemin est un exécutable » (le système de fichiers, en vrai)
     */
    static ShellElection elect(OperatingSystem os, Map<String, String> env, Predicate<Path> executable) {
        try {
            return os == OperatingSystem.WINDOWS
                    ? electOnWindows(env, executable)
                    : electOnPosix(env, executable);
        } catch (RuntimeException e) {
            // Sondage impossible (droits, système de fichiers exotique) : on retombe exactement sur
            // le comportement d'avant cette subfeature. Une élection ne coûte jamais le démarrage.
            return fallbackFor(os);
        }
    }

    private static ShellElection electOnPosix(Map<String, String> env, Predicate<Path> executable) {
        // /bin/sh d'abord : c'est ce que le runner utilisait déjà, et il est présent partout.
        for (String candidate : List.of("/bin/sh", "/bin/bash", "/usr/bin/sh", "/usr/bin/bash")) {
            Path path = pathOf(candidate);
            if (path != null && executable.test(path)) {
                return posix(candidate);
            }
        }
        Path onPath = searchPath(env, executable, List.of("sh", "bash"), ":", false);
        return onPath != null ? posix(onPath.toString()) : posix("/bin/sh");
    }

    private static ShellElection electOnWindows(Map<String, String> env, Predicate<Path> executable) {
        // 1 — bash POSIX. Git pour Windows d'abord, à ses emplacements d'installation : on sait qui
        // il est, alors qu'un `bash.exe` du PATH demande à être disqualifié (D3).
        for (Path candidate : gitBashCandidates(env)) {
            if (executable.test(candidate)) {
                return new ShellElection(Kind.POSIX, candidate.toString(), List.of("-c"));
            }
        }
        Path onPath = searchPath(env, executable, List.of("bash.exe", "bash"), ";", true);
        if (onPath != null) {
            return new ShellElection(Kind.POSIX, onPath.toString(), List.of("-c"));
        }

        // 2 — PowerShell. `pwsh` (PowerShell 7) avant `powershell.exe` (Windows PowerShell 5.1).
        Path powerShell =
                searchPath(env, executable, List.of("pwsh.exe", "powershell.exe"), ";", false);
        if (powerShell == null) {
            Path system = pathOf(env.get("SystemRoot"));
            if (system != null) {
                Path builtin = system.resolve("System32")
                        .resolve("WindowsPowerShell").resolve("v1.0").resolve("powershell.exe");
                if (executable.test(builtin)) {
                    powerShell = builtin;
                }
            }
        }
        if (powerShell != null) {
            return new ShellElection(Kind.POWERSHELL, powerShell.toString(), POWERSHELL_OPTIONS);
        }

        // 3 — cmd.exe, le repli garanti (D7). Il ne comprend ni `ls` ni `grep`, et c'est justement
        // pour cela que le genre est déclaré : la consigne système dira la vérité au modèle.
        Path comSpec = pathOf(env.get("ComSpec"));
        String executablePath = comSpec != null && executable.test(comSpec)
                ? comSpec.toString()
                : "cmd.exe";
        return new ShellElection(Kind.CMD, executablePath, List.of("/c"));
    }

    /** Emplacements d'installation de Git pour Windows, dans l'ordre où on les rencontre. */
    private static List<Path> gitBashCandidates(Map<String, String> env) {
        List<Path> candidates = new ArrayList<>();
        for (String variable : List.of("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)",
                "LOCALAPPDATA")) {
            Path base = pathOf(env.get(variable));
            if (base == null) {
                continue;
            }
            // LOCALAPPDATA héberge l'installation « pour cet utilisateur seulement ».
            Path git = "LOCALAPPDATA".equals(variable)
                    ? base.resolve("Programs").resolve("Git")
                    : base.resolve("Git");
            candidates.add(git.resolve("bin").resolve("bash.exe"));
            candidates.add(git.resolve("usr").resolve("bin").resolve("bash.exe"));
        }
        return candidates;
    }

    /**
     * Premier des {@code names} trouvé dans le {@code PATH}.
     *
     * <p>Le séparateur est celui du <b>système élu</b>, pas celui de la JVM courante : c'est ce qui
     * rend l'élection Windows vérifiable depuis une machine d'intégration continue sous Linux.</p>
     *
     * @param separator           séparateur d'entrées du {@code PATH} ({@code ;} ou {@code :})
     * @param excludeWslLaunchers écarte les dossiers Windows où {@code bash.exe} est le lanceur WSL
     */
    private static Path searchPath(Map<String, String> env, Predicate<Path> executable,
            List<String> names, String separator, boolean excludeWslLaunchers) {
        String raw = env.get("PATH");
        if (raw == null || raw.isBlank()) {
            raw = env.get("Path"); // Windows n'est pas sensible à la casse, la carte d'env l'est.
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (String entry : raw.split(java.util.regex.Pattern.quote(separator))) {
            if (entry.isBlank()) {
                continue;
            }
            Path directory = pathOf(entry.trim());
            if (directory == null || (excludeWslLaunchers && isWslLauncherDirectory(directory))) {
                continue;
            }
            for (String name : names) {
                Path candidate = directory.resolve(name);
                if (executable.test(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** Vrai si ce dossier est un de ceux où {@code bash.exe} est le lanceur WSL (D3). */
    static boolean isWslLauncherDirectory(Path directory) {
        Path name = directory.getFileName();
        if (name == null) {
            return false;
        }
        return WSL_LAUNCHER_DIRECTORIES.contains(name.toString().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------- PowerShell

    /**
     * Options de PowerShell. {@code -NoProfile} : le profil de l'utilisateur ne doit pas changer ce
     * que la commande fait. {@code -NonInteractive} : rien n'attendra une saisie — le runner ferme
     * déjà l'entrée standard, une invite pendrait jusqu'au délai.
     */
    private static final List<String> POWERSHELL_OPTIONS =
            List.of("-NoProfile", "-NonInteractive", "-EncodedCommand");

    /**
     * Commande encodée pour {@code -EncodedCommand} : base64 d'UTF-16LE, comme PowerShell l'attend.
     *
     * <p><b>Pourquoi encoder</b> : sous Windows, {@link ProcessBuilder} recompose une ligne de
     * commande unique en citant les arguments, et cette citation ne survit pas aux guillemets
     * internes. Une commande aussi banale que {@code Select-String -Pattern "class Foo"} arriverait
     * déformée. L'encodage supprime la question — c'est ce que la documentation de PowerShell
     * recommande pour une commande complexe. SF-38-23 a déjà montré ce que coûte un shell qui avale
     * une partie de ce qu'on lui donne.</p>
     *
     * <p>Le script encodé <b>propage le code de sortie</b> : {@code powershell.exe} rendrait sinon
     * {@code 0} même après une commande native en échec, et le contrat du runner (§2.3) promet le
     * code réel.</p>
     */
    static String powerShellArguments(String command) {
        String script = "$LASTEXITCODE = 0" + System.lineSeparator()
                + command + System.lineSeparator()
                + "exit $LASTEXITCODE";
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    // ------------------------------------------------------------------ outils

    /** Élection retenue quand le sondage a échoué : le comportement d'avant SF-38-27, à l'identique. */
    private static ShellElection fallbackFor(OperatingSystem os) {
        return os == OperatingSystem.WINDOWS
                ? new ShellElection(Kind.CMD, "cmd.exe", List.of("/c"))
                : posix("/bin/sh");
    }

    private static ShellElection posix(String executable) {
        return new ShellElection(Kind.POSIX, executable, List.of("-c"));
    }

    private static Path pathOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            return null; // Entrée de PATH illisible : ignorée, jamais fatale.
        }
    }

    private static boolean isExecutable(Path path) {
        try {
            return Files.isRegularFile(path) && Files.isExecutable(path);
        } catch (RuntimeException e) {
            return false; // Chemin refusé par le système : ce n'est pas un candidat.
        }
    }
}
