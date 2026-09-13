package fr.claudegateway.runner.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Le <b>processus enfant</b> qui exécute le vrai runner (F-111 / SF-111-02) : le même exécutable
 * {@code java} que le lanceur, les mêmes options de JVM utiles, les mêmes arguments, le même
 * environnement, et la même console ({@link ProcessBuilder#inheritIO()}).
 *
 * <p>« Mêmes options » est pris au sens de ce qui <b>change le comportement réseau</b> du runner :
 * proxy, magasin de confiance, agents, mémoire. Le chemin de classes, lui, est remplacé — c'est
 * tout l'objet de l'opération.</p>
 */
public final class JavaChild {

    /** Classe d'entrée du vrai runner : jamais le lanceur, pour ne pas se relancer soi-même. */
    public static final String RUNNER_MAIN = "fr.claudegateway.runner.RunnerMain";

    /** Posé dans l'environnement de l'enfant : l'identifiant du lanceur (surveillance, déclaration). */
    public static final String LAUNCHER_PID_ENV = "CLAUDE_RUNNER_LAUNCHER_PID";

    /** Préfixes des options de JVM transmises à l'enfant. */
    private static final List<String> FORWARDED_PREFIXES = List.of("-D", "-X", "-javaagent:",
            "-agentlib:", "-agentpath:", "--add-opens=", "--add-exports=", "--enable-native-access=");

    /** Propriétés relues quand la ligne de commande du lanceur n'est pas lisible (Windows). */
    private static final List<String> FORWARDED_PROPERTIES = List.of("http.proxyHost",
            "http.proxyPort", "https.proxyHost", "https.proxyPort", "http.nonProxyHosts",
            "socksProxyHost", "socksProxyPort", "java.net.useSystemProxies",
            "javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword",
            "javax.net.ssl.trustStoreType", "jdk.http.auth.tunneling.disabledSchemes",
            "jdk.http.auth.proxying.disabledSchemes");

    private final Path javaExecutable;
    private final List<String> jvmOptions;
    private final String mainClass;
    private final Map<String, String> extraEnv;

    public JavaChild(Path javaExecutable, List<String> jvmOptions, String mainClass,
            Map<String, String> extraEnv) {
        this.javaExecutable = javaExecutable;
        this.jvmOptions = List.copyOf(jvmOptions);
        this.mainClass = mainClass;
        this.extraEnv = Map.copyOf(extraEnv);
    }

    /** L'enfant du runner réel, pour ce lanceur-ci. */
    public static JavaChild forThisLauncher(Map<String, String> extraEnv) {
        return new JavaChild(javaExecutable(System.getProperty("java.home"),
                        System.getProperty("os.name", "")),
                currentJvmOptions(), RUNNER_MAIN, extraEnv);
    }

    /** La ligne de commande de l'enfant (sans le démarrer) : c'est elle que les tests lisent. */
    public List<String> command(Path jar, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.addAll(jvmOptions);
        command.add("-cp");
        command.add(jar.toString());
        command.add(mainClass);
        command.addAll(args);
        return command;
    }

    /** Démarre l'enfant sur la console du lanceur. */
    public Process start(Path jar, List<String> args) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command(jar, args)).inheritIO();
        // L'environnement est HÉRITÉ par défaut (proxy, CLAUDE_TEAMS_DEBUG_PORT…) : on n'ajoute que
        // ce qui dit à l'enfant qu'il a un lanceur.
        builder.environment().putAll(extraEnv);
        return builder.start();
    }

    /**
     * L'exécutable {@code java} de la JVM du lanceur : {@code java.home/bin/java(.exe)} — celui du
     * paquet autonome quand le lanceur y tourne, jamais un {@code java} trouvé dans le {@code PATH}.
     */
    static Path javaExecutable(String javaHome, String osName) {
        boolean windows = osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows");
        Path candidate = Path.of(javaHome == null ? "." : javaHome, "bin", windows ? "java.exe" : "java");
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        return ProcessHandle.current().info().command().map(Path::of).orElse(candidate);
    }

    /**
     * Les options de JVM du lanceur à transmettre : lues sur sa ligne de commande quand le système
     * la rend (Linux, macOS), sinon reconstruites depuis les propriétés réseau posées.
     */
    static List<String> currentJvmOptions() {
        List<String> fromCommandLine = ProcessHandle.current().info().arguments()
                .map(arguments -> jvmOptionsOf(List.of(arguments)))
                .orElse(null);
        if (fromCommandLine != null) {
            return fromCommandLine;
        }
        List<String> options = new ArrayList<>();
        for (String key : FORWARDED_PROPERTIES) {
            String value = System.getProperty(key);
            if (value != null) {
                options.add("-D" + key + "=" + value);
            }
        }
        return options;
    }

    /**
     * Les options de JVM d'une ligne de commande, c'est-à-dire ce qui précède {@code -jar},
     * {@code -cp} ou la classe principale, filtrées aux préfixes qui décrivent un comportement.
     */
    static List<String> jvmOptionsOf(List<String> arguments) {
        List<String> options = new ArrayList<>();
        for (String argument : arguments) {
            if (argument.equals("-jar") || argument.equals("-cp") || argument.equals("-classpath")
                    || argument.equals("--class-path") || !argument.startsWith("-")) {
                break;
            }
            if (FORWARDED_PREFIXES.stream().anyMatch(argument::startsWith)) {
                options.add(argument);
            }
        }
        return options;
    }
}
