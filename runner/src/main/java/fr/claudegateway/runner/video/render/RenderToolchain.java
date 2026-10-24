package fr.claudegateway.runner.video.render;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.OperatingSystem;
import fr.claudegateway.runner.teams.LocalToolchain;
import fr.claudegateway.runner.teams.ToolchainUnavailableException;

/**
 * <b>La chaîne de rendu du poste</b> (F-215 / SF-215-02) : Node ≥ 22, {@code hyperframes} épinglé, Chrome sans
 * écran, ffmpeg/ffprobe — cherchés d'abord, rapatriés seulement s'ils manquent, dans le dossier d'outils du
 * runner (jamais d'installation système, jamais de droits administrateur).
 *
 * <h2>Jamais de contournement</h2>
 * <p>Un poste verrouillé (registre npm bloqué, exécution interdite hors des dossiers approuvés, proxy) produit
 * un {@link RenderFailure} qui dit <b>quoi</b> et <b>à qui demander quoi</b> — et rien d'autre n'est tenté : la
 * politique du poste prime (règle « porte de validation vs policy client »).</p>
 *
 * <h2>Garde-fous du rapatriement</h2>
 * <p>Adresses et versions <b>en dur</b> ici, jamais reçues d'un appel d'outil ; destination fixe (dossier
 * d'outils) ; HTTPS par le client réseau du runner (proxy d'entreprise et racine Zscaler compris).</p>
 */
public final class RenderToolchain {

    /** La version de Node rapatriée quand le poste n'a pas de Node 22+. */
    public static final String NODE_VERSION = "22.22.1";
    /** La version épinglée d'HyperFrames (cadrage F-215 : rendu reproductible). */
    public static final String HYPERFRAMES_VERSION = "0.8.193";
    /**
     * La version épinglée de GSAP (F-215 / SF-215-03), installée à côté d'HyperFrames : la composition la charge
     * en local ({@code briques/gsap.min.js}), jamais depuis un CDN au rendu. Jamais vendue dans notre dépôt.
     */
    public static final String GSAP_VERSION = "3.13.0";
    static final int NODE_MIN_MAJOR = 22;

    static final long VERIFY_TIMEOUT_MS = 20_000L;
    static final long INSTALL_TIMEOUT_MS = 600_000L;
    static final long BROWSER_TIMEOUT_MS = 600_000L;

    private static final Pattern NODE_VERSION_LINE = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** ffmpeg et ffprobe, résolus par la chaîne F-90 (PATH, copie rapatriée, rapatriement). */
    public interface Media {
        Path ffmpeg();

        Path ffprobe();
    }

    private final Path toolsDir;
    private final OperatingSystem os;
    private final String arch;
    private final RenderProcesses processes;
    private final LocalToolchain.Downloader downloader;
    private final Function<String, Path> pathLookup;
    private final Media media;
    private final Function<OperatingSystem, Optional<Path>> systemChrome;
    private final String basePath;
    private volatile Prepared prepared;

    public RenderToolchain(Path toolsDir, OperatingSystem os, String arch, RenderProcesses processes,
            LocalToolchain.Downloader downloader, Function<String, Path> pathLookup, Media media,
            Function<OperatingSystem, Optional<Path>> systemChrome, String basePath) {
        this.toolsDir = toolsDir;
        this.os = os == null ? OperatingSystem.OTHER : os;
        this.arch = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
        this.processes = processes;
        this.downloader = downloader;
        this.pathLookup = pathLookup;
        this.media = media;
        this.systemChrome = systemChrome;
        this.basePath = basePath == null ? "" : basePath;
    }

    /**
     * La chaîne prête à rendre. Une fois réussie, elle est gardée pour la vie du runner.
     *
     * @param say ce qui se dit pendant l'attente (téléchargements, installation)
     * @throws RenderFailure ce qui manque, et le remède
     */
    public synchronized Prepared prepare(Consumer<String> say) {
        Prepared known = prepared;
        if (known != null && Files.isRegularFile(known.hyperframes())) {
            return known;
        }
        Consumer<String> tell = say == null ? m -> { } : say;
        Path node = node(tell);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HYPERFRAMES_NO_TELEMETRY", "1");
        env.put("HYPERFRAMES_NO_UPDATE_CHECK", "1");
        env.put("HYPERFRAMES_SKIP_SKILLS", "1");
        env.put("PATH", node.getParent() + (basePath.isEmpty() ? "" : File.pathSeparator + basePath));
        Path entry = hyperframes(node, env, tell);
        Path gsap = gsap(node, env, tell);
        try {
            env.put("HYPERFRAMES_FFMPEG_PATH", media.ffmpeg().toString());
            env.put("HYPERFRAMES_FFPROBE_PATH", media.ffprobe().toString());
        } catch (ToolchainUnavailableException e) {
            throw new RenderFailure("ffmpeg indisponible : " + e.getMessage(), e.remedy());
        }
        String browser = browser(node, entry, env, tell);
        Prepared ready = new Prepared(node, entry, gsap, Map.copyOf(env),
                "Node " + nodeVersion(node) + " (" + node + "), hyperframes " + HYPERFRAMES_VERSION + ", gsap "
                        + GSAP_VERSION + ", " + browser
                        + ", ffmpeg " + env.get("HYPERFRAMES_FFMPEG_PATH"));
        prepared = ready;
        return ready;
    }

    // ------------------------------------------------------------------ Node

    private Path node(Consumer<String> say) {
        Path onPath = pathLookup.apply(os == OperatingSystem.WINDOWS ? "node.exe" : "node");
        if (onPath != null) {
            int major = major(onPath);
            if (major >= NODE_MIN_MAJOR) {
                return onPath;
            }
            say.accept("Node " + (major < 0 ? "illisible" : major) + " trouvé sur ce poste : il faut Node "
                    + NODE_MIN_MAJOR + " ou plus. Je prends une copie privée de Node " + NODE_VERSION
                    + " dans le dossier d'outils du runner (votre Node n'est pas touché).");
        }
        Path managed = managedNode();
        if (!Files.isRegularFile(managed)) {
            download(say);
        }
        verifyManaged(managed);
        return managed;
    }

    private Path managedNodeDir() {
        return toolsDir.resolve("node-v" + NODE_VERSION);
    }

    private Path managedNode() {
        Path dir = managedNodeDir();
        return os == OperatingSystem.WINDOWS ? dir.resolve("node.exe") : dir.resolve("bin").resolve("node");
    }

    /** Le Node rapatrié doit s'exécuter ; s'il est refusé, c'est la politique du poste — on s'arrête là. */
    private void verifyManaged(Path node) {
        String reason;
        try {
            RenderProcesses.Result result = processes.run(List.of(node.toString(), "--version"), null, Map.of(),
                    VERIFY_TIMEOUT_MS);
            if (result.succeeded() && parseMajor(String.join("\n", result.output())) >= NODE_MIN_MAJOR) {
                return;
            }
            reason = result.timedOut() ? "délai dépassé" : "code " + result.exitCode() + " " + result.tail(2);
        } catch (IOException e) {
            reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        throw new RenderFailure("Node " + NODE_VERSION + " est sur ce poste (" + node + ") mais son exécution "
                + "est refusée (" + reason.strip() + ") : c'est la politique du poste, je ne la contourne pas.",
                "Demandez à votre support d'installer Node.js " + NODE_MIN_MAJOR + " LTS (" + nodeAdvice()
                        + ") ou d'autoriser le dossier d'outils du runner, puis relancez video_render.");
    }

    private void download(Consumer<String> say) {
        String platform = platform();
        if (platform == null) {
            throw new RenderFailure("Node " + NODE_MIN_MAJOR + " introuvable sur ce poste, et je n'ai pas de version "
                    + "à rapatrier pour ce système (" + os + ", " + arch + ").",
                    "Installez Node.js " + NODE_MIN_MAJOR + " LTS : " + nodeAdvice() + ", puis relancez video_render.");
        }
        String file = "node-v" + NODE_VERSION + "-" + platform + extension();
        String url = "https://nodejs.org/dist/v" + NODE_VERSION + "/" + file;
        Path archive = null;
        Path staging = null;
        try (fr.claudegateway.runner.RunnerActivity.Scope ignored =
                fr.claudegateway.runner.RunnerActivity.begin(fr.claudegateway.runner.RunnerActivity.DOWNLOAD)) {
            Files.createDirectories(toolsDir);
            say.accept("Premier rendu vidéo : je rapatrie Node " + NODE_VERSION + " depuis nodejs.org (≈ 30 Mo, une "
                    + "seule fois) dans le dossier d'outils du runner. Vous pouvez aussi installer Node 22 vous-même : "
                    + nodeAdvice() + ".");
            archive = Files.createTempFile(toolsDir, "node-", ".download");
            if (downloader.fetch(url, archive) <= 0) {
                throw new IOException("archive vide");
            }
            staging = Files.createTempDirectory(toolsDir, "node-staging-");
            unpack(archive, staging);
            Path top = staging.resolve("node-v" + NODE_VERSION + "-" + platform);
            if (!Files.isDirectory(top)) {
                throw new IOException("archive inattendue (dossier " + top.getFileName() + " absent)");
            }
            Files.move(top, managedNodeDir(), StandardCopyOption.REPLACE_EXISTING);
            say.accept("Node " + NODE_VERSION + " est en place : " + managedNodeDir() + ".");
        } catch (IOException | RuntimeException e) {
            if (e instanceof RenderFailure failure) {
                throw failure;
            }
            throw new RenderFailure("Node " + NODE_MIN_MAJOR + " introuvable sur ce poste et son rapatriement depuis "
                    + "nodejs.org a échoué (" + reason(e) + ").",
                    "Installez Node.js " + NODE_MIN_MAJOR + " LTS : " + nodeAdvice() + " — ou, si le téléchargement "
                            + "est bloqué par le proxy ou la politique du poste, demandez-le à votre support ; puis "
                            + "relancez video_render.");
        } finally {
            deleteQuietly(archive);
            deleteQuietly(staging);
        }
    }

    private void unpack(Path archive, Path into) throws IOException {
        if (os == OperatingSystem.WINDOWS) {
            try (InputStream stream = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(stream)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path target = into.resolve(entry.getName()).normalize();
                    if (!target.startsWith(into)) {
                        throw new IOException("entrée d'archive hors du dossier : " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            return;
        }
        RenderProcesses.Result result = processes.run(
                List.of("tar", "-xf", archive.toAbsolutePath().toString(), "-C", into.toAbsolutePath().toString()),
                null, Map.of(), INSTALL_TIMEOUT_MS);
        if (!result.succeeded()) {
            throw new IOException("tar a refusé l'archive : " + result.tail(2));
        }
    }

    /** Le suffixe de plateforme des archives officielles, ou {@code null} si ce système n'en a pas. */
    String platform() {
        String cpu = switch (arch) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> null;
        };
        if (cpu == null) {
            return null;
        }
        return switch (os) {
            case WINDOWS -> "win-" + cpu;
            case MACOS -> "darwin-" + cpu;
            case LINUX -> "linux-" + cpu;
            default -> null;
        };
    }

    private String extension() {
        return switch (os) {
            case WINDOWS -> ".zip";
            case MACOS -> ".tar.gz";
            default -> ".tar.xz";
        };
    }

    private String nodeAdvice() {
        return switch (os) {
            case WINDOWS -> "winget install OpenJS.NodeJS.LTS";
            case MACOS -> "brew install node@22";
            case LINUX -> "paquet Node.js 22 de votre distribution ou nodejs.org";
            default -> "nodejs.org";
        };
    }

    private int major(Path node) {
        try {
            RenderProcesses.Result result = processes.run(List.of(node.toString(), "--version"), null, Map.of(),
                    VERIFY_TIMEOUT_MS);
            return result.succeeded() ? parseMajor(String.join("\n", result.output())) : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private String nodeVersion(Path node) {
        try {
            RenderProcesses.Result result = processes.run(List.of(node.toString(), "--version"), null, Map.of(),
                    VERIFY_TIMEOUT_MS);
            return result.output().isEmpty() ? "?" : result.output().get(0).strip();
        } catch (IOException e) {
            return "?";
        }
    }

    static int parseMajor(String text) {
        Matcher m = NODE_VERSION_LINE.matcher(text == null ? "" : text);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    // ------------------------------------------------------------------ hyperframes

    Path hyperframesDir() {
        return toolsDir.resolve("hyperframes-" + HYPERFRAMES_VERSION);
    }

    private Path hyperframes(Path node, Map<String, String> env, Consumer<String> say) {
        Path dir = hyperframesDir();
        Path pkg = dir.resolve("node_modules").resolve("hyperframes").resolve("package.json");
        Path entry = dir.resolve("node_modules").resolve("hyperframes").resolve("bin").resolve("hyperframes.mjs");
        if (HYPERFRAMES_VERSION.equals(installedVersion(pkg)) && Files.isRegularFile(entry)) {
            return entry;
        }
        List<String> npm = npm(node);
        say.accept("J'installe hyperframes " + HYPERFRAMES_VERSION + " (rendu de vidéos, open source) dans le dossier "
                + "d'outils du runner, avec le registre npm de ce poste. Une à deux minutes, une seule fois.");
        RenderProcesses.Result result;
        try {
            Files.createDirectories(dir);
            List<String> command = new ArrayList<>(npm);
            command.addAll(List.of("install", "--prefix", dir.toString(), "--no-audit", "--no-fund",
                    "--loglevel=error", "hyperframes@" + HYPERFRAMES_VERSION));
            result = processes.run(command, dir, env, INSTALL_TIMEOUT_MS);
        } catch (IOException e) {
            throw npmRefused(reason(e));
        }
        if (!result.succeeded() || !HYPERFRAMES_VERSION.equals(installedVersion(pkg)) || !Files.isRegularFile(entry)) {
            throw npmRefused(result.timedOut() ? "délai dépassé" : result.tail(3));
        }
        say.accept("hyperframes " + HYPERFRAMES_VERSION + " est en place.");
        return entry;
    }

    /**
     * GSAP épinglé, dans le même dossier qu'HyperFrames (F-215 / SF-215-03). Un poste préparé avant SF-215-03 a déjà
     * HyperFrames : seul GSAP est alors ajouté, une fois.
     */
    private Path gsap(Path node, Map<String, String> env, Consumer<String> say) {
        Path dir = hyperframesDir();
        Path pkg = dir.resolve("node_modules").resolve("gsap").resolve("package.json");
        Path min = dir.resolve("node_modules").resolve("gsap").resolve("dist").resolve("gsap.min.js");
        if (GSAP_VERSION.equals(installedVersion(pkg)) && Files.isRegularFile(min)) {
            return min;
        }
        say.accept("J'installe GSAP " + GSAP_VERSION + " (animations des vidéos) à côté d'hyperframes.");
        RenderProcesses.Result result;
        try {
            List<String> command = new ArrayList<>(npm(node));
            command.addAll(List.of("install", "--prefix", dir.toString(), "--no-audit", "--no-fund",
                    "--loglevel=error", "gsap@" + GSAP_VERSION));
            result = processes.run(command, dir, env, INSTALL_TIMEOUT_MS);
        } catch (IOException e) {
            throw gsapRefused(reason(e));
        }
        if (!result.succeeded() || !GSAP_VERSION.equals(installedVersion(pkg)) || !Files.isRegularFile(min)) {
            throw gsapRefused(result.timedOut() ? "délai dépassé" : result.tail(3));
        }
        return min;
    }

    private static RenderFailure gsapRefused(String reason) {
        return new RenderFailure("Installation de GSAP " + GSAP_VERSION + " impossible (" + reason + ").",
                "Le registre npm est sans doute inaccessible ou bloqué par le proxy ou la politique du poste : je ne le "
                        + "contourne pas. Demandez l'accès au registre npm, ou l'installation de gsap@" + GSAP_VERSION
                        + ", à votre support ; puis relancez video_render.");
    }

    private RenderFailure npmRefused(String reason) {
        return new RenderFailure("Installation de hyperframes " + HYPERFRAMES_VERSION + " impossible (" + reason
                + ").", "Le registre npm est sans doute inaccessible ou bloqué par le proxy ou la politique du poste : "
                + "je ne le contourne pas. Demandez l'accès au registre npm, ou l'installation de hyperframes@"
                + HYPERFRAMES_VERSION + ", à votre support ; puis relancez video_render.");
    }

    /** Le npm de ce Node : {@code npm-cli.js} à côté de lui, sinon celui du PATH. */
    private List<String> npm(Path node) {
        Path dir = node.getParent();
        Path cli = os == OperatingSystem.WINDOWS
                ? dir.resolve("node_modules").resolve("npm").resolve("bin").resolve("npm-cli.js")
                : dir.getParent() == null ? null
                        : dir.getParent().resolve("lib").resolve("node_modules").resolve("npm").resolve("bin")
                                .resolve("npm-cli.js");
        if (cli != null && Files.isRegularFile(cli)) {
            return List.of(node.toString(), cli.toString());
        }
        Path onPath = pathLookup.apply(os == OperatingSystem.WINDOWS ? "npm.cmd" : "npm");
        if (onPath != null) {
            return List.of(onPath.toString());
        }
        throw new RenderFailure("npm introuvable à côté de Node (" + node + ").",
                "Installez Node.js " + NODE_MIN_MAJOR + " LTS complet (avec npm) : " + nodeAdvice()
                        + ", puis relancez video_render.");
    }

    static String installedVersion(Path packageJson) {
        try {
            return Files.isRegularFile(packageJson) ? MAPPER.readTree(packageJson.toFile()).path("version").asText("")
                    : "";
        } catch (IOException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ Chrome

    private String browser(Path node, Path entry, Map<String, String> env, Consumer<String> say) {
        say.accept("Je prépare le Chrome sans écran d'HyperFrames (téléchargé une seule fois s'il manque).");
        String reason;
        try {
            RenderProcesses.Result result = processes.run(
                    List.of(node.toString(), entry.toString(), "browser", "ensure"), entry.getParent(), env,
                    BROWSER_TIMEOUT_MS);
            if (result.succeeded()) {
                return "Chrome épinglé d'HyperFrames";
            }
            reason = result.timedOut() ? "délai dépassé" : result.tail(2);
        } catch (IOException e) {
            reason = reason(e);
        }
        Optional<Path> chrome = systemChrome.apply(os);
        if (chrome.isPresent()) {
            env.put("HYPERFRAMES_BROWSER_PATH", chrome.get().toString());
            say.accept("Le Chrome d'HyperFrames n'a pas pu être préparé (" + reason + ") : j'utilise le Chrome "
                    + "installé sur ce poste.");
            return "Chrome du poste (" + chrome.get() + ")";
        }
        throw new RenderFailure("Chrome sans écran indisponible (" + reason + ").",
                "Le téléchargement du Chrome d'HyperFrames a échoué et aucun Chrome n'est installé sur ce poste : "
                        + "installez Google Chrome, ou demandez à votre support d'autoriser son téléchargement ; puis "
                        + "relancez video_render.");
    }

    // ------------------------------------------------------------------ util

    private static String reason(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Un reste dans le dossier temporaire ne gêne rien.
                }
            });
        } catch (IOException ignored) {
            // Idem.
        }
    }

    /**
     * La chaîne prête.
     *
     * @param node        le Node à lancer
     * @param hyperframes le point d'entrée {@code hyperframes.mjs}
     * @param gsap        le {@code gsap.min.js} épinglé, copié dans les compositions (SF-215-03)
     * @param env         l'environnement des commandes HyperFrames
     * @param report      ce qui a été retenu, en une phrase
     */
    public record Prepared(Path node, Path hyperframes, Path gsap, Map<String, String> env, String report) {
    }

    /** La chaîne déjà prête (préparée pendant la vie de ce runner), ou {@code null}. */
    public Prepared ready() {
        Prepared known = prepared;
        return known != null && Files.isRegularFile(known.hyperframes()) ? known : null;
    }
}
