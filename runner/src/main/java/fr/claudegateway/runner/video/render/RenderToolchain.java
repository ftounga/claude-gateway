package fr.claudegateway.runner.video.render;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.OperatingSystem;
import fr.claudegateway.runner.teams.ChromePaths;
import fr.claudegateway.runner.teams.ToolchainUnavailableException;

/**
 * <b>La chaîne de rendu du poste</b> (F-215 / SF-215-02, revue par SF-215-06) : Node ≥ 22, {@code hyperframes}
 * épinglé, GSAP, ffmpeg/ffprobe et le Chrome (ou Edge) du poste.
 *
 * <h2>Rien n'est pris sur internet (SF-215-06)</h2>
 * <p>Le 2026-10-25, sur un poste derrière un proxy d'entreprise, le rapatriement de Node depuis {@code nodejs.org}
 * a reçu un {@code HTTP 403} ; npm, {@code browser ensure} et ffmpeg dépendaient eux aussi d'internet. Désormais,
 * dans cet ordre :</p>
 * <ol>
 *   <li>la <b>chaîne du portail déjà en place</b> ({@link VideoToolchainBundle#installed()}) ;</li>
 *   <li>sinon un <b>Node ≥ 22 et hyperframes {@value #HYPERFRAMES_VERSION} + GSAP déjà présents</b> sur le poste
 *       (Node du PATH ou copie privée d'une version précédente du runner ; installation d'une version précédente)
 *       avec un ffmpeg/ffprobe présent sans téléchargement ;</li>
 *   <li>sinon <b>le paquet signé du portail</b> ({@link VideoToolchainBundle#ensure}).</li>
 * </ol>
 * <p>Plus jamais {@code nodejs.org}, le registre npm, {@code browser ensure} ni un CDN : le navigateur est celui du
 * poste ({@link ChromePaths}), et HyperFrames tourne avec télémétrie, vérification de mise à jour et installations
 * automatiques coupées.</p>
 *
 * <h2>Jamais de contournement</h2>
 * <p>Un poste qui refuse d'exécuter Node (AppLocker, {@code noexec}) produit un {@link RenderFailure} qui dit
 * <b>quoi</b> et <b>à qui demander quoi</b> — et rien d'autre n'est tenté : la politique du poste prime.</p>
 */
public final class RenderToolchain {

    /** La version de Node du paquet du portail ; aussi celle d'une copie privée d'une version précédente. */
    public static final String NODE_VERSION = "22.22.1";
    /** La version épinglée d'HyperFrames (cadrage F-215 : rendu reproductible). */
    public static final String HYPERFRAMES_VERSION = "0.8.193";
    /**
     * La version épinglée de GSAP (F-215 / SF-215-03), à côté d'HyperFrames : la composition la charge en local
     * ({@code briques/gsap.min.js}), jamais depuis un CDN au rendu.
     */
    public static final String GSAP_VERSION = "3.13.0";
    static final int NODE_MIN_MAJOR = 22;
    static final long VERIFY_TIMEOUT_MS = 20_000L;

    /**
     * L'environnement qui coupe tout appel d'HyperFrames vers internet (noms vérifiés dans le {@code dist} de
     * hyperframes 0.8.193) : télémétrie, vérification et installation de mise à jour, avis de « skills », et
     * installation à la volée de dépendances optionnelles.
     */
    static final Map<String, String> OFFLINE_ENV = Map.of(
            "HYPERFRAMES_NO_TELEMETRY", "1",
            "DO_NOT_TRACK", "1",
            "HYPERFRAMES_NO_UPDATE_CHECK", "1",
            "HYPERFRAMES_NO_AUTO_INSTALL", "1",
            "HYPERFRAMES_SKIP_SKILLS", "1",
            "HYPERFRAMES_NO_OPTIONAL_INSTALL", "1");

    private static final Pattern NODE_VERSION_LINE = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** ffmpeg et ffprobe du poste, résolus par la chaîne F-90 (PATH, copie déjà rapatriée). */
    public interface Media {
        Path ffmpeg();

        Path ffprobe();

        /** Les deux sont-ils là sans rien télécharger ? */
        boolean presentWithoutDownload();
    }

    private final Path toolsDir;
    private final OperatingSystem os;
    private final RenderProcesses processes;
    private final VideoToolchainBundle bundle;
    private final Function<String, Path> pathLookup;
    private final Media media;
    private final Function<OperatingSystem, Optional<Path>> browser;
    private final String basePath;
    private volatile Prepared prepared;

    /**
     * @param browser le navigateur Chromium du poste (Chrome, Edge…) — en production
     *                {@link ChromePaths#executable}, surcharge {@code CLAUDE_TEAMS_CHROME_PATH} comprise
     */
    public RenderToolchain(Path toolsDir, OperatingSystem os, RenderProcesses processes, VideoToolchainBundle bundle,
            Function<String, Path> pathLookup, Media media, Function<OperatingSystem, Optional<Path>> browser,
            String basePath) {
        this.toolsDir = toolsDir;
        this.os = os == null ? OperatingSystem.OTHER : os;
        this.processes = processes;
        this.bundle = bundle;
        this.pathLookup = pathLookup;
        this.media = media;
        this.browser = browser;
        this.basePath = basePath == null ? "" : basePath;
    }

    /**
     * La chaîne prête à rendre. Une fois réussie, elle est gardée pour la vie du runner.
     *
     * @param say ce qui se dit pendant l'attente (récupération depuis le portail)
     * @throws RenderFailure ce qui manque, et le remède
     */
    public synchronized Prepared prepare(Consumer<String> say) {
        Prepared known = prepared;
        if (known != null && Files.isRegularFile(known.hyperframes())) {
            return known;
        }
        Consumer<String> tell = say == null ? m -> { } : say;
        Chain chain = resolve(tell);
        verifyNode(chain);
        Path chrome = browser();
        Map<String, String> env = new LinkedHashMap<>(OFFLINE_ENV);
        env.put("HYPERFRAMES_BROWSER_PATH", chrome.toString());
        env.put("HYPERFRAMES_FFMPEG_PATH", chain.ffmpeg().toString());
        env.put("HYPERFRAMES_FFPROBE_PATH", chain.ffprobe().toString());
        env.put("PATH", chain.node().getParent() + File.pathSeparator + chain.ffmpeg().getParent()
                + (basePath.isEmpty() ? "" : File.pathSeparator + basePath));
        Prepared ready = new Prepared(chain.node(), chain.hyperframes(), chain.gsap(), Map.copyOf(env),
                "Node " + nodeVersion(chain.node()) + " (" + chain.origin() + "), hyperframes " + HYPERFRAMES_VERSION
                        + ", gsap " + GSAP_VERSION + ", navigateur " + chrome + ", ffmpeg " + chain.ffmpeg());
        prepared = ready;
        return ready;
    }

    /** L'avancement de la préparation en cours, en clair (vide s'il n'y a rien à dire). */
    public String progress() {
        return bundle == null ? "" : bundle.progress();
    }

    // ------------------------------------------------------------------ résolution

    private Chain resolve(Consumer<String> say) {
        Optional<VideoToolchainBundle.Installed> installed = bundle.installed();
        if (installed.isPresent()) {
            return fromBundle(installed.get());
        }
        Chain local = alreadyOnThePoste(say);
        if (local != null) {
            return local;
        }
        if (!bundle.supported()) {
            throw new RenderFailure("Le portail n'a pas de chaîne vidéo pour ce système, et ce poste n'a pas Node "
                    + NODE_MIN_MAJOR + "+ avec hyperframes " + HYPERFRAMES_VERSION + ".",
                    "Demandez à votre support d'installer Node.js " + NODE_MIN_MAJOR + " LTS et hyperframes "
                            + HYPERFRAMES_VERSION + " (avec gsap " + GSAP_VERSION + ") sur ce poste ; je les "
                            + "utiliserai tels quels.");
        }
        return fromBundle(bundle.ensure(say));
    }

    private Chain fromBundle(VideoToolchainBundle.Installed installed) {
        return new Chain(installed.node(), installed.hyperframes(), installed.gsap(), installed.ffmpeg(),
                installed.ffprobe(), "chaîne vidéo du portail, " + installed.home());
    }

    /**
     * Node ≥ 22, hyperframes {@value #HYPERFRAMES_VERSION} et GSAP déjà présents (une version précédente du runner
     * les a installés), et ffmpeg/ffprobe présents : rien à télécharger. Sinon {@code null}.
     */
    private Chain alreadyOnThePoste(Consumer<String> say) {
        Path dir = toolsDir.resolve("hyperframes-" + HYPERFRAMES_VERSION).resolve("node_modules");
        Path entry = dir.resolve("hyperframes").resolve("bin").resolve("hyperframes.mjs");
        Path gsap = dir.resolve("gsap").resolve("dist").resolve("gsap.min.js");
        if (!HYPERFRAMES_VERSION.equals(installedVersion(dir.resolve("hyperframes").resolve("package.json")))
                || !Files.isRegularFile(entry) || !GSAP_VERSION.equals(installedVersion(
                        dir.resolve("gsap").resolve("package.json"))) || !Files.isRegularFile(gsap)) {
            return null;
        }
        Path node = localNode(say);
        if (node == null || !media.presentWithoutDownload()) {
            return null;
        }
        try {
            return new Chain(node, entry, gsap, media.ffmpeg(), media.ffprobe(), node.toString());
        } catch (ToolchainUnavailableException e) {
            return null;
        }
    }

    /** Un Node ≥ 22 déjà là : celui du PATH, sinon la copie privée d'une version précédente du runner. */
    private Path localNode(Consumer<String> say) {
        Path onPath = pathLookup.apply(os == OperatingSystem.WINDOWS ? "node.exe" : "node");
        if (onPath != null) {
            int major = major(onPath);
            if (major >= NODE_MIN_MAJOR) {
                return onPath;
            }
            say.accept("Node " + (major < 0 ? "illisible" : major) + " trouvé sur ce poste : il faut Node "
                    + NODE_MIN_MAJOR + " ou plus. Je prends celui de la chaîne vidéo du portail (votre Node n'est "
                    + "pas touché).");
        }
        Path legacyDir = toolsDir.resolve("node-v" + NODE_VERSION);
        Path legacy = os == OperatingSystem.WINDOWS ? legacyDir.resolve("node.exe")
                : legacyDir.resolve("bin").resolve("node");
        return Files.isRegularFile(legacy) && major(legacy) >= NODE_MIN_MAJOR ? legacy : null;
    }

    /** Node doit s'exécuter ; s'il est refusé, c'est la politique du poste — on s'arrête là. */
    private void verifyNode(Chain chain) {
        String reason;
        try {
            RenderProcesses.Result result = processes.run(List.of(chain.node().toString(), "--version"), null,
                    Map.of(), VERIFY_TIMEOUT_MS);
            if (result.succeeded() && parseMajor(String.join("\n", result.output())) >= NODE_MIN_MAJOR) {
                return;
            }
            reason = result.timedOut() ? "délai dépassé" : "code " + result.exitCode() + " " + result.tail(2);
        } catch (IOException e) {
            reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        throw new RenderFailure("Node est sur ce poste (" + chain.node() + ") mais son exécution est refusée ("
                + reason.strip() + ") : c'est la politique du poste, je ne la contourne pas.",
                "Demandez à votre support d'autoriser l'exécution dans le dossier d'outils du runner (" + toolsDir
                        + "), ou d'installer Node.js " + NODE_MIN_MAJOR + " LTS et hyperframes " + HYPERFRAMES_VERSION
                        + " ; puis relancez video_render.");
    }

    // ------------------------------------------------------------------ navigateur

    /** Le Chrome (ou Edge) du poste : HyperFrames ne télécharge plus le sien. */
    private Path browser() {
        Optional<Path> found = browser.apply(os);
        if (found.isPresent() && Files.isRegularFile(found.get())) {
            return found.get();
        }
        if (found.isPresent()) {
            throw new RenderFailure("Le navigateur indiqué pour le rendu est introuvable (" + found.get() + ").",
                    "Corrigez la variable " + ChromePaths.PATH_ENV + " (chemin complet de Chrome ou d'Edge), ou "
                            + "retirez-la ; puis relancez le runner et video_render.");
        }
        throw new RenderFailure("Aucun Chrome ni Edge trouvé sur ce poste : HyperFrames en a besoin pour dessiner "
                + "les images de la vidéo (je n'en télécharge pas).", "Installez Google Chrome ou Microsoft Edge ("
                + browserAdvice() + "), ou, s'il est installé ailleurs, indiquez son chemin complet dans la variable "
                + ChromePaths.PATH_ENV + " avant de lancer le runner ; puis relancez video_render.");
    }

    private String browserAdvice() {
        return switch (os) {
            case WINDOWS -> "Edge est livré avec Windows ; sinon le portail logiciel de votre entreprise";
            case MACOS -> "depuis le portail logiciel de votre entreprise (Self Service) ou google.com/chrome";
            default -> "paquet google-chrome ou chromium de votre distribution";
        };
    }

    // ------------------------------------------------------------------ util

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

    static String installedVersion(Path packageJson) {
        try {
            return Files.isRegularFile(packageJson) ? MAPPER.readTree(packageJson.toFile()).path("version").asText("")
                    : "";
        } catch (IOException e) {
            return "";
        }
    }

    private record Chain(Path node, Path hyperframes, Path gsap, Path ffmpeg, Path ffprobe, String origin) {
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
