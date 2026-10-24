package fr.claudegateway.runner;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Ce que la capture de preuve doit savoir du poste pour ressembler au vrai terminal de l'utilisateur
 * (F-212 / SF-212-01, amendement PO du 2026-10-24) : système, compte, nom d'hôte court, dossier
 * personnel, shell interactif — et <b>quel shell exécute la session</b>.
 *
 * <p>Sur Linux et macOS, la session tourne sous le shell de l'utilisateur ({@code $SHELL -c}, non
 * login : pas de bavardage de profil) quand il parle POSIX (bash, zsh, sh, dash, ksh) — la syntaxe des
 * commandes reste donc celle que la gateway annonce au modèle. Un {@code fish}, qui ne parle pas
 * POSIX, retombe sur le shell élu. Sur Windows, la session tourne sous le shell élu (c'est sa syntaxe
 * que le modèle écrit) et c'est lui que l'image imite.</p>
 *
 * @param os               {@code linux}, {@code macos} ou {@code windows}
 * @param user             nom du compte
 * @param host             nom d'hôte court (avant le premier point), comme l'affiche un shell
 * @param home             dossier personnel (chemin absolu), pour l'abréviation {@code ~}
 * @param interactiveShell shell de l'utilisateur : {@code bash}, {@code zsh}, {@code fish}, {@code sh},
 *                         {@code powershell}, {@code cmd}…
 * @param execShell        shell qui exécute réellement la session (même vocabulaire)
 * @param execExecutable   exécutable du {@code $SHELL} retenu, ou {@code null} : le shell élu sert alors
 */
public record ProofHost(String os, String user, String host, String home, String interactiveShell,
        String execShell, String execExecutable) {

    private static final Set<String> POSIX_SHELLS = Set.of("bash", "zsh", "sh", "dash", "ksh");

    /** Le poste courant. */
    public static ProofHost detect(ShellElection elected) {
        return detect(OperatingSystem.current(), System.getenv(), System.getProperty("user.name", ""),
                System.getProperty("user.home", ""), localHostName(), elected,
                path -> Files.isRegularFile(path) && Files.isExecutable(path));
    }

    static ProofHost detect(OperatingSystem system, Map<String, String> env, String user, String home,
            String hostName, ShellElection elected, Predicate<Path> executable) {
        String os = switch (system) {
            case WINDOWS -> "windows";
            case MACOS -> "macos";
            default -> "linux";
        };
        String host = shortHost(firstNonBlank(hostName, env.get("HOSTNAME"), env.get("COMPUTERNAME")));
        String electedName = electedName(elected);
        if (system == OperatingSystem.WINDOWS) {
            return new ProofHost(os, clip(user), host, clip(home), electedName, electedName, null);
        }
        String shellPath = env.get("SHELL");
        String interactive = shellPath == null || shellPath.isBlank() ? electedName : baseName(shellPath);
        String executableShell = null;
        String exec = electedName;
        if (POSIX_SHELLS.contains(interactive) && elected.kind() == ShellElection.Kind.POSIX) {
            Path path = pathOf(shellPath);
            if (path != null && executable.test(path)) {
                executableShell = path.toString();
                exec = interactive;
            }
        }
        return new ProofHost(os, clip(user), host, clip(home), interactive, exec, executableShell);
    }

    /** La ligne de commande de la session : {@code $SHELL -c script} si retenu, sinon le shell élu. */
    List<String> commandLine(ShellElection elected, String script) {
        return execExecutable != null ? List.of(execExecutable, "-c", script) : elected.commandLine(script);
    }

    private static String electedName(ShellElection elected) {
        return switch (elected.kind()) {
            case POWERSHELL -> "powershell";
            case CMD -> "cmd";
            default -> {
                // Le shell élu est un chemin (/bin/sh, …\Git\bin\bash.exe) : son nom suffit.
                String description = elected.description();
                int dash = description.lastIndexOf(" — ");
                String name = dash >= 0 ? baseName(description.substring(dash + 3)) : "sh";
                yield name.isBlank() ? "sh" : name;
            }
        };
    }

    static String shortHost(String host) {
        if (host == null || host.isBlank()) {
            return "localhost";
        }
        String trimmed = host.strip();
        int dot = trimmed.indexOf('.');
        return clip(dot > 0 ? trimmed.substring(0, dot) : trimmed);
    }

    static String baseName(String path) {
        String normalized = path.strip().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (name.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static String localHostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "";
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Path pathOf(String value) {
        try {
            return value == null || value.isBlank() ? null : Path.of(value.strip());
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 256 ? value.substring(0, 256) : value;
    }
}
