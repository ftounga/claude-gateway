package fr.claudegateway.runner.video.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>La chaîne vidéo servie par le portail</b> (F-215 / SF-215-06) : Node 22, hyperframes, GSAP et ffmpeg/ffprobe en
 * <b>un paquet signé</b>, pris à la gateway que ce runner joint déjà — plus jamais sur internet.
 *
 * <h2>Pourquoi</h2>
 * <p>Le 2026-10-25, sur un poste derrière le proxy d'une banque, le rapatriement de Node depuis {@code nodejs.org}
 * a reçu un {@code HTTP 403} ; npm, le Chrome d'HyperFrames et ffmpeg dépendaient eux aussi d'internet. Le portail,
 * lui, est joignable par définition : c'est par lui que le poste travaille.</p>
 *
 * <h2>Garde-fous</h2>
 * <ol>
 *   <li>Le <b>manifeste</b> (empreinte et taille de chaque paquet) est <b>signé</b> avec la clé de mise à jour du
 *       runner (F-111), vérifiée avec la clé embarquée : un proxy qui intercepte TLS ou une gateway compromise ne
 *       peuvent pas servir un autre paquet.</li>
 *   <li>Le paquet téléchargé doit avoir la <b>taille</b> et le <b>sha256</b> du manifeste, sinon il est supprimé.</li>
 *   <li>Il est extrait en Java ({@link TarGz} : aucun lien, aucune entrée hors du dossier) dans un dossier
 *       temporaire, puis déplacé d'un bloc ; un marqueur de complétude dit « en place ». Rien n'est jamais laissé à
 *       moitié écrit.</li>
 *   <li>Un téléchargement interrompu <b>reprend</b> où il s'était arrêté ({@code Range}).</li>
 * </ol>
 *
 * <p>Un seul téléchargement à la fois dans ce processus, quel que soit l'appelant (rendu F-215 ou ffmpeg de
 * F-190).</p>
 */
public final class VideoToolchainBundle {

    /** La version du paquet que ce runner demande — égale à {@code TOOLCHAIN_VERSION} de {@code tools/video-toolchain}. */
    public static final String VERSION = "0.8.193-r1";
    /** Le préfixe des routes de la gateway (contrat). */
    public static final String ROUTE = "/runner/toolchain/video/";
    /** L'en-tête qui porte le jeton runner (contrat, D9). */
    public static final String TOKEN_HEADER = "X-Runner-Token";
    /** Les plateformes qui ont un paquet (contrat). */
    public static final List<String> PLATFORMS = List.of("darwin-arm64", "darwin-x64", "linux-x64", "win-x64");
    /** Le genre écrit dans le manifeste. */
    static final String KIND = "video-toolchain";
    /** Au-delà, ce n'est plus le paquet attendu. */
    static final long MAX_BUNDLE_BYTES = 1024L * 1024 * 1024;
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MARKER = ".complete";

    private final Path toolsDir;
    private final OperatingSystem os;
    private final String platform;
    private final String gatewayBaseUrl;
    private final Supplier<String> token;
    private final Supplier<HttpClient> client;
    private final BiPredicate<byte[], String> signatureCheck;
    private volatile String progress = "";

    /**
     * @param signatureCheck la vérification Ed25519 du manifeste — en production, la clé embarquée du runner
     *                       ({@code UpdateVerifier.embedded()::signatureValid})
     */
    public VideoToolchainBundle(Path toolsDir, OperatingSystem os, String arch, String gatewayBaseUrl,
            Supplier<String> token, Supplier<HttpClient> client, BiPredicate<byte[], String> signatureCheck) {
        this.toolsDir = toolsDir;
        this.os = os == null ? OperatingSystem.OTHER : os;
        this.platform = platformOf(this.os, arch);
        this.gatewayBaseUrl = gatewayBaseUrl == null ? "" : gatewayBaseUrl.replaceAll("/+$", "");
        this.token = token;
        this.client = client;
        this.signatureCheck = signatureCheck;
    }

    /** La plateforme d'un paquet ({@code darwin-arm64}…), ou {@code null} si ce système n'en a pas. */
    public static String platformOf(OperatingSystem os, String arch) {
        String cpu = switch (arch == null ? "" : arch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> null;
        };
        if (cpu == null || os == null) {
            return null;
        }
        String candidate = switch (os) {
            case WINDOWS -> "win-" + cpu;
            case MACOS -> "darwin-" + cpu;
            case LINUX -> "linux-" + cpu;
            default -> null;
        };
        return PLATFORMS.contains(candidate) ? candidate : null;
    }

    /** La plateforme de ce poste, ou {@code null} si aucun paquet n'existe pour elle. */
    public String platform() {
        return platform;
    }

    /** Ce système a-t-il un paquet ? */
    public boolean supported() {
        return platform != null;
    }

    /** L'avancement du téléchargement en cours, en clair (vide hors téléchargement). */
    public String progress() {
        return progress;
    }

    /** Le dossier du paquet en place. */
    public Path home() {
        return toolsDir.resolve("video-toolchain-" + VERSION);
    }

    /** Le paquet déjà en place sur ce poste (marqueur et fichiers présents), sans rien télécharger. */
    public Optional<Installed> installed() {
        Path home = home();
        if (!Files.isRegularFile(home.resolve(MARKER))) {
            return Optional.empty();
        }
        Installed installed = layout(home);
        return installed.complete() ? Optional.of(installed) : Optional.empty();
    }

    /**
     * Le paquet en place — récupéré depuis le portail s'il le faut.
     *
     * @param say ce qui se dit pendant l'attente
     * @throws RenderFailure la cause précise et le remède
     */
    public Installed ensure(Consumer<String> say) {
        Consumer<String> tell = say == null ? m -> { } : say;
        LOCK.lock();
        try (fr.claudegateway.runner.RunnerActivity.Scope ignored =
                fr.claudegateway.runner.RunnerActivity.begin(fr.claudegateway.runner.RunnerActivity.DOWNLOAD)) {
            Optional<Installed> already = installed();
            if (already.isPresent()) {
                return already.get();
            }
            return fetch(tell);
        } finally {
            progress = "";
            LOCK.unlock();
        }
    }

    // ------------------------------------------------------------------ récupération

    private Installed fetch(Consumer<String> say) {
        if (platform == null) {
            throw new RenderFailure("Le portail n'a pas de chaîne vidéo pour ce système (" + os + ").",
                    "Demandez à votre support d'installer Node.js 22 LTS et hyperframes "
                            + RenderToolchain.HYPERFRAMES_VERSION + " sur ce poste ; je les utiliserai tels quels.");
        }
        String clearToken = token == null ? "" : String.valueOf(token.get()).strip();
        if (clearToken.isEmpty() || "null".equals(clearToken)) {
            throw new RenderFailure("Ce poste n'a pas de jeton runner : je ne peux pas demander la chaîne vidéo au "
                    + "portail.", "Réappairez ce poste depuis la Forge (« Réappairer ce poste… »), puis relancez.");
        }
        if (gatewayBaseUrl.isEmpty()) {
            throw new RenderFailure("L'adresse du portail est inconnue de ce runner.",
                    "Relancez le runner avec l'adresse de la gateway (--gateway), puis relancez.");
        }
        String base = gatewayBaseUrl + ROUTE + VERSION;
        progress = "lecture du manifeste";
        byte[] manifestBytes = getBytes(base + "/manifest", clearToken, "le manifeste");
        String signature = new String(getBytes(base + "/signature", clearToken, "la signature"),
                StandardCharsets.US_ASCII).strip();
        if (!signatureCheck.test(manifestBytes, signature)) {
            throw new RenderFailure("La signature du manifeste de la chaîne vidéo servi par le portail n'est pas "
                    + "valide pour la clé de ce runner : rien n'est téléchargé ni installé.",
                    "Prévenez l'administrateur du portail (manifeste " + VERSION + " à re-signer). Rien à faire "
                            + "sur votre poste.");
        }
        Entry entry = entry(manifestBytes);
        say.accept("Premier rendu vidéo : je récupère la chaîne vidéo depuis le portail (Node 22, hyperframes "
                + RenderToolchain.HYPERFRAMES_VERSION + ", GSAP, ffmpeg), ≈ " + mib(entry.size())
                + ", une seule fois, dans le dossier d'outils du runner. Rien n'est pris sur internet.");
        Path part = toolsDir.resolve("video-toolchain-" + VERSION + "-" + platform + ".tar.gz.part");
        Path staging = null;
        try {
            Files.createDirectories(toolsDir);
            download(base + "/" + platform, clearToken, part, entry, say);
            progress = "vérification et installation";
            staging = toolsDir.resolve("video-toolchain-" + VERSION + ".staging-"
                    + UUID.randomUUID().toString().substring(0, 8));
            TarGz.extract(part, staging);
            Installed unpacked = layout(staging);
            if (!unpacked.complete()) {
                throw new IOException("paquet incomplet (" + unpacked.missing() + " absent)");
            }
            checkBundleJson(staging);
            for (Path executable : List.of(unpacked.node(), unpacked.ffmpeg(), unpacked.ffprobe())) {
                executable.toFile().setExecutable(true, false);
            }
            Files.writeString(staging.resolve(MARKER), entry.sha256() + "\n", StandardCharsets.US_ASCII);
            Path home = home();
            deleteQuietly(home);
            Files.move(staging, home, StandardCopyOption.ATOMIC_MOVE);
            staging = null;
            Files.deleteIfExists(part);
            say.accept("La chaîne vidéo est en place : " + home + " (" + mib(entry.size())
                    + " récupérés depuis le portail). Elle ne sera plus téléchargée.");
            return layout(home);
        } catch (RenderFailure failure) {
            throw failure;
        } catch (IOException | RuntimeException e) {
            throw new RenderFailure("La chaîne vidéo récupérée depuis le portail n'a pas pu être installée ("
                    + reason(e) + ").", "Vérifiez l'espace disque du dossier d'outils (" + toolsDir
                    + "), puis relancez : rien n'a été laissé à moitié installé.");
        } finally {
            deleteQuietly(staging);
        }
    }

    /** Le paquet de cette plateforme d'après le manifeste vérifié. */
    private Entry entry(byte[] manifestBytes) {
        JsonNode manifest;
        try {
            manifest = MAPPER.readTree(manifestBytes);
        } catch (IOException e) {
            throw invalidManifest("illisible");
        }
        if (!KIND.equals(manifest.path("kind").asText()) || !VERSION.equals(manifest.path("version").asText())) {
            throw invalidManifest("ce n'est pas la chaîne vidéo " + VERSION);
        }
        if (!RenderToolchain.HYPERFRAMES_VERSION.equals(manifest.path("hyperframes").asText())) {
            throw invalidManifest("hyperframes " + manifest.path("hyperframes").asText() + " au lieu de "
                    + RenderToolchain.HYPERFRAMES_VERSION);
        }
        JsonNode bundle = manifest.path("bundles").path(platform);
        String sha256 = bundle.path("sha256").asText("").toLowerCase(Locale.ROOT);
        long size = bundle.path("size").asLong(-1);
        if (bundle.isMissingNode()) {
            throw new RenderFailure("Le portail ne sert pas la chaîne vidéo " + VERSION + " pour " + platform + ".",
                    "Prévenez l'administrateur du portail (paquet " + platform + " à publier). Rien à faire sur "
                            + "votre poste.");
        }
        if (!sha256.matches("[0-9a-f]{64}") || size <= 0 || size > MAX_BUNDLE_BYTES) {
            throw invalidManifest("empreinte ou taille du paquet " + platform + " invalide");
        }
        return new Entry(sha256, size);
    }

    private static RenderFailure invalidManifest(String why) {
        return new RenderFailure("Le manifeste de la chaîne vidéo servi par le portail est invalide (" + why + ").",
                "Prévenez l'administrateur du portail. Rien à faire sur votre poste.");
    }

    /**
     * Télécharge le paquet dans {@code part}, en reprenant un {@code .part} précédent, puis vérifie taille et sha256.
     * Un fichier qui ne correspond pas est supprimé : le prochain essai repart de zéro.
     */
    private void download(String url, String clearToken, Path part, Entry entry, Consumer<String> say)
            throws IOException {
        long have = Files.isRegularFile(part) ? Files.size(part) : 0;
        if (have >= entry.size()) {
            if (have == entry.size() && entry.sha256().equals(sha256(part))) {
                return;
            }
            Files.deleteIfExists(part);
            have = 0;
        }
        if (have > 0) {
            say.accept("Reprise du téléchargement de la chaîne vidéo à " + mib(have) + " sur " + mib(entry.size())
                    + ".");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
                .header(TOKEN_HEADER, clearToken).GET();
        if (have > 0) {
            request.header("Range", "bytes=" + have + "-");
        }
        HttpResponse<InputStream> response = send(request.build(), "le paquet " + platform);
        boolean append;
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 206 && have > 0) {
                append = true;
            } else if (status == 200) {
                append = false;
                have = 0;
            } else if (status == 416) {
                // Le reste attendu n'existe pas côté portail : ce début ne vaut plus rien, on repartira de zéro.
                Files.deleteIfExists(part);
                throw new RenderFailure("Le portail ne reconnaît pas la reprise du téléchargement de la chaîne vidéo "
                        + "(HTTP 416) : je repartirai de zéro.", "Relancez.");
            } else {
                throw httpFailure(status, "le paquet " + platform);
            }
            try (OutputStream out = Files.newOutputStream(part, append
                    ? new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                    : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE})) {
                byte[] buffer = new byte[65_536];
                long total = have;
                int lastQuarter = (int) (total * 4 / entry.size());
                int read;
                while ((read = body.read(buffer)) > 0) {
                    total += read;
                    if (total > entry.size()) {
                        throw new OversizedBody();
                    }
                    out.write(buffer, 0, read);
                    int percent = (int) (total * 100 / entry.size());
                    progress = "téléchargement depuis le portail : " + percent + " % de " + mib(entry.size());
                    int quarter = (int) (total * 4 / entry.size());
                    if (quarter > lastQuarter && quarter < 4) {
                        say.accept("Chaîne vidéo : " + percent + " % (" + mib(total) + " sur " + mib(entry.size())
                                + ").");
                        lastQuarter = quarter;
                    }
                }
            }
        } catch (OversizedBody e) {
            Files.deleteIfExists(part);
            throw new RenderFailure("Le portail envoie un paquet plus gros que le manifeste signé ne l'annonce : je "
                    + "l'ai supprimé, rien n'est installé.", "Prévenez l'administrateur du portail. Rien à faire "
                    + "sur votre poste.");
        } catch (IOException e) {
            throw wrapNetwork(e, "le paquet " + platform);
        }
        long size = Files.size(part);
        if (size < entry.size()) {
            throw new RenderFailure("Le téléchargement de la chaîne vidéo s'est interrompu à " + mib(size) + " sur "
                    + mib(entry.size()) + ".", "Relancez : le téléchargement reprendra où il s'est arrêté.");
        }
        if (!entry.sha256().equals(sha256(part))) {
            Files.deleteIfExists(part);
            throw new RenderFailure("Le paquet de la chaîne vidéo reçu ne correspond pas au manifeste signé (paquet "
                    + "altéré ou tronqué en route) : je l'ai supprimé, rien n'est installé.",
                    "Relancez. Si cela se reproduit, prévenez l'administrateur du portail.");
        }
    }

    private byte[] getBytes(String url, String clearToken, String what) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
                .header(TOKEN_HEADER, clearToken).GET().build();
        HttpResponse<InputStream> response = send(request, what);
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw httpFailure(response.statusCode(), what);
            }
            byte[] bytes = body.readNBytes(256 * 1024);
            if (bytes.length == 0) {
                throw new RenderFailure("Le portail a renvoyé " + what + " de la chaîne vidéo vide.",
                        "Prévenez l'administrateur du portail. Rien à faire sur votre poste.");
            }
            return bytes;
        } catch (IOException e) {
            throw wrapNetwork(e, what);
        }
    }

    private HttpResponse<InputStream> send(HttpRequest request, String what) {
        try {
            return client.get().send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw wrapNetwork(e, what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RenderFailure("Récupération de la chaîne vidéo interrompue.", "Relancez.");
        }
    }

    private RenderFailure httpFailure(int status, String what) {
        return switch (status) {
            case 401 -> new RenderFailure("Le portail a refusé le jeton de ce poste en demandant " + what
                    + " de la chaîne vidéo (HTTP 401).", "Réappairez ce poste depuis la Forge (« Réappairer ce "
                    + "poste… »), puis relancez.");
            case 404 -> new RenderFailure("Le portail ne sert pas la chaîne vidéo " + VERSION
                    + (what.startsWith("le paquet") ? " pour " + platform : "") + " (HTTP 404 sur " + what + ").",
                    "Prévenez l'administrateur du portail : la chaîne vidéo n'y est pas encore publiée. Rien à faire "
                            + "sur votre poste.");
            default -> new RenderFailure("Le portail a répondu HTTP " + status + " en servant " + what
                    + " de la chaîne vidéo.", "Relancez dans un moment ; si cela persiste, prévenez l'administrateur "
                    + "du portail.");
        };
    }

    private RenderFailure wrapNetwork(IOException e, String what) {
        String cause = e instanceof HttpTimeoutException ? "délai dépassé"
                : e instanceof ConnectException ? "connexion refusée" + (e.getMessage() == null ? ""
                        : " : " + e.getMessage())
                : reason(e);
        return new RenderFailure("Le portail n'a pas pu être joint pour " + what + " de la chaîne vidéo (" + cause
                + ").", "C'est la même route que la connexion de ce runner à la gateway (proxy compris) : vérifiez "
                + "que le runner est bien connecté, puis relancez ; le téléchargement reprendra où il s'est "
                + "arrêté.");
    }

    /** Le corps dépasse la taille signée : le {@code .part} ne vaut plus rien. */
    private static final class OversizedBody extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private void checkBundleJson(Path dir) throws IOException {
        Path file = dir.resolve("bundle.json");
        if (!Files.isRegularFile(file)) {
            throw new IOException("bundle.json absent");
        }
        JsonNode bundle = MAPPER.readTree(file.toFile());
        if (!VERSION.equals(bundle.path("version").asText()) || !platform.equals(bundle.path("platform").asText())) {
            throw new IOException("paquet " + bundle.path("version").asText() + "/" + bundle.path("platform").asText()
                    + " au lieu de " + VERSION + "/" + platform);
        }
    }

    // ------------------------------------------------------------------ disposition

    private Installed layout(Path home) {
        boolean windows = os == OperatingSystem.WINDOWS;
        Path node = windows ? home.resolve("node").resolve("node.exe")
                : home.resolve("node").resolve("bin").resolve("node");
        Path modules = home.resolve("hyperframes").resolve("node_modules");
        return new Installed(home, node,
                modules.resolve("hyperframes").resolve("bin").resolve("hyperframes.mjs"),
                modules.resolve("gsap").resolve("dist").resolve("gsap.min.js"),
                home.resolve("ffmpeg").resolve(windows ? "ffmpeg.exe" : "ffmpeg"),
                home.resolve("ffmpeg").resolve(windows ? "ffprobe.exe" : "ffprobe"));
    }

    /**
     * Le paquet en place.
     *
     * @param home        son dossier
     * @param node        Node 22
     * @param hyperframes le point d'entrée {@code hyperframes.mjs}
     * @param gsap        {@code gsap.min.js}
     * @param ffmpeg      ffmpeg
     * @param ffprobe     ffprobe
     */
    public record Installed(Path home, Path node, Path hyperframes, Path gsap, Path ffmpeg, Path ffprobe) {

        boolean complete() {
            return missing() == null;
        }

        String missing() {
            for (Path p : List.of(node, hyperframes, gsap, ffmpeg, ffprobe)) {
                if (!Files.isRegularFile(p)) {
                    return home.relativize(p).toString();
                }
            }
            return null;
        }
    }

    private record Entry(String sha256, long size) {
    }

    // ------------------------------------------------------------------ util

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65_536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String mib(long bytes) {
        return String.format(Locale.FRENCH, "%.0f Mo", Math.max(1, bytes / (1024.0 * 1024.0)));
    }

    private static String reason(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message.strip();
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
                    // Un reste temporaire ne gêne rien : il n'a pas de marqueur.
                }
            });
        } catch (IOException ignored) {
            // Idem.
        }
    }
}
