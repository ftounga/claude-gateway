package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>L'outillage local, résolu une fois et téléchargé au premier usage</b> (F-90 / SF-90-01,
 * décision <b>D3</b> du cadrage).
 *
 * <h2>Jamais embarqué</h2>
 *
 * <p>« Le paquet autonome fait déjà 40 Mo, et la plupart des utilisateurs n'ouvriront jamais
 * Teams. » Aucun binaire n'est donc livré avec le runner. Il est cherché — puis, seulement s'il
 * manque, rapatrié.</p>
 *
 * <h2>L'ordre compte, et il commence par ne rien télécharger</h2>
 *
 * <ol>
 *   <li>le {@code PATH} du poste — un {@code ffmpeg} installé par l'entreprise est signé par elle,
 *       et c'est mieux que tout ce que nous pourrions rapatrier ;</li>
 *   <li>la copie déjà rapatriée sous {@code .claude-runner/teams/tools/} ;</li>
 *   <li>le téléchargement, <b>et seulement alors</b>.</li>
 * </ol>
 *
 * <h2>Le téléchargement se voit et se dit</h2>
 *
 * <p>Une phrase <b>avant</b> — ce qui va être pris, d'où, et pour quoi faire — et une phrase
 * <b>après</b> — où il a été posé, ce qu'il pèse. « C'est une minute d'attente la première fois, pas
 * une panne » : l'utilisateur ne peut le comprendre que si on le lui dit pendant qu'il attend.</p>
 *
 * <h2>Jamais de copie à moitié écrite</h2>
 *
 * <p>Tout passe par un fichier temporaire et un dossier temporaire, déplacés d'un bloc à la fin.
 * Un téléchargement interrompu laisse la machine <b>exactement</b> dans l'état d'avant : un binaire
 * tronqué en place serait pire que pas de binaire du tout — il échouerait en silence, plus tard, et
 * sur autre chose.</p>
 *
 * <h2>Réutilisable par F-91, et c'est voulu</h2>
 *
 * <p>Rien ici ne connaît {@code ffmpeg} : cette classe résout « un {@link LocalTool} ». Le modèle de
 * transcription de F-91 se décrira dans {@link LocalTool} et passera par ce même chemin, avec les
 * mêmes annonces et les mêmes refus.</p>
 */
public final class LocalToolchain {

    /** Délai de téléchargement : un binaire de 80 Mo sur une liaison d'entreprise. */
    static final long DOWNLOAD_TIMEOUT_MS = 300_000L;
    /** Délai de l'appel de vérification ({@code -version}). */
    static final long VERIFY_TIMEOUT_MS = 20_000L;
    /** Au-delà, ce n'est plus l'archive attendue : on refuse plutôt que de remplir le disque. */
    static final long MAX_ARCHIVE_BYTES = 400L * 1024 * 1024;

    private final TeamsWorkFolder folder;
    private final OperatingSystem os;
    private final ProcessRunner processes;
    private final Downloader downloader;
    private final Consumer<String> say;
    private final Map<String, Path> resolved = new ConcurrentHashMap<>();
    private final PathLookup pathLookup;

    public LocalToolchain(TeamsWorkFolder folder, ProcessRunner processes, Consumer<String> say) {
        this(folder, OperatingSystem.current(), processes, httpDownloader(), say,
                LocalToolchain::lookupOnPath);
    }

    LocalToolchain(TeamsWorkFolder folder, OperatingSystem os, ProcessRunner processes,
            Downloader downloader, Consumer<String> say, PathLookup pathLookup) {
        this.folder = folder;
        this.os = os == null ? OperatingSystem.OTHER : os;
        this.processes = processes;
        this.downloader = downloader;
        this.say = say == null ? message -> { } : say;
        this.pathLookup = pathLookup;
    }

    /**
     * Le chemin de l'outil, prêt à être lancé.
     *
     * @throws ToolchainUnavailableException quand il n'est ni présent ni rapatriable —
     *                                       <b>avec le remède</b>, jamais un échec nu
     */
    public Path require(LocalTool tool) {
        Path known = resolved.get(tool.name());
        if (known != null && Files.isExecutable(known)) {
            return known;
        }
        Path found = onPath(tool);
        if (found == null) {
            found = alreadyDownloaded(tool);
        }
        if (found == null) {
            found = download(tool);
        }
        verify(tool, found);
        resolved.put(tool.name(), found);
        return found;
    }

    /** L'outil est-il déjà là, sans rien télécharger ? Sert à le dire avant de commencer. */
    public boolean availableWithoutDownload(LocalTool tool) {
        return onPath(tool) != null || alreadyDownloaded(tool) != null;
    }

    // ------------------------------------------------------------------ recherche

    /** Étape 1 : le {@code PATH} du poste. */
    private Path onPath(LocalTool tool) {
        for (String executable : tool.executables()) {
            Path found = pathLookup.find(executableName(executable));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Étape 2 : ce qui a déjà été rapatrié. */
    private Path alreadyDownloaded(LocalTool tool) {
        Path home = folder.toolsDir().resolve(TeamsWorkFolder.safe(tool.name()));
        if (!Files.isDirectory(home)) {
            return null;
        }
        for (String executable : tool.executables()) {
            Path candidate = home.resolve(executableName(executable));
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ téléchargement

    /** Étape 3, et seulement alors. */
    private Path download(LocalTool tool) {
        String url = tool.downloadFor(os);
        if (url.isEmpty()) {
            throw new ToolchainUnavailableException(
                    "« " + tool.name() + " » est introuvable sur cette machine, et je n'ai pas de "
                            + "version à rapatrier pour ce système.",
                    "Installez-le vous-même : " + tool.installAdvice(os)
                            + ". Puis redemandez : je le trouverai dans le PATH.");
        }
        Path home = folder.toolsDir().resolve(TeamsWorkFolder.safe(tool.name()));
        Path staging = null;
        Path archive = null;
        try {
            folder.ensure(folder.toolsDir());
            // D3 : le téléchargement SE DIT — avant, parce que c'est pendant qu'on attend qu'on a
            // besoin de savoir pourquoi.
            say.accept("Premier usage : je dois rapatrier « " + tool.name() + " » depuis " + host(url)
                    + " pour " + tool.purpose() + ". Il n'est pas livré avec le runner (il pèse "
                    + "trop lourd pour les neuf utilisateurs sur dix qui ne s'en serviront jamais). "
                    + "C'est une minute d'attente, une seule fois. Vous pouvez aussi l'installer "
                    + "vous-même : " + tool.installAdvice(os) + ".");
            archive = Files.createTempFile("claude-runner-", ".download");
            long bytes = downloader.fetch(url, archive);
            if (bytes <= 0) {
                throw new IOException("archive vide");
            }
            staging = Files.createTempDirectory("claude-runner-tool-");
            unpack(tool, archive, staging);
            Path executable = locate(tool, staging);
            if (executable == null) {
                throw new IOException("aucun exécutable « " + tool.name() + " » dans l'archive");
            }
            Path target = install(home, executable, tool);
            say.accept("« " + tool.name() + " » est en place : " + target + " (" + mib(bytes)
                    + " téléchargés). Il ne sera plus retéléchargé.");
            return target;
        } catch (IOException | RuntimeException e) {
            // Aucune copie partielle ne survit : le prochain appel repart d'un état net.
            deleteQuietly(staging);
            throw new ToolchainUnavailableException(
                    "Le rapatriement de « " + tool.name() + " » n'a pas abouti (" + reason(e) + ").",
                    "Installez-le vous-même : " + tool.installAdvice(os)
                            + ". Puis redemandez. Rien n'a été laissé à moitié écrit sur la machine.",
                    e);
        } finally {
            deleteQuietly(archive);
            deleteQuietly(staging);
        }
    }

    /** Pose l'exécutable à sa place définitive, d'un seul geste. */
    private Path install(Path home, Path executable, LocalTool tool) throws IOException {
        Files.createDirectories(home);
        Path target = home.resolve(executableName(tool.executables().isEmpty()
                ? tool.name() : tool.executables().get(0)));
        Files.move(executable, target, StandardCopyOption.REPLACE_EXISTING);
        makeExecutable(target);
        return target;
    }

    private void unpack(LocalTool tool, Path archive, Path into) throws IOException {
        LocalTool.Archive kind = tool.archiveFor(os);
        if (kind == LocalTool.Archive.ZIP) {
            unzip(archive, into);
            return;
        }
        if (kind == LocalTool.Archive.TAR_XZ) {
            // Le `tar` du système sait ouvrir le .tar.xz ; le JDK ne sait pas. Déléguer à l'outil
            // universel du système vaut mieux que d'embarquer un décodeur XZ pour un seul usage.
            ProcessRunner.ProcessResult result = processes.run(
                    List.of("tar", "-xf", archive.toAbsolutePath().toString()), into,
                    DOWNLOAD_TIMEOUT_MS);
            if (!result.succeeded()) {
                throw new IOException("tar a refusé l'archive : " + result.tail(3));
            }
            return;
        }
        throw new IOException("genre d'archive inconnu pour ce système");
    }

    /** Décompression ZIP, avec la garde de traversée : une entrée n'est jamais un chemin. */
    private static void unzip(Path archive, Path into) throws IOException {
        try (InputStream stream = Files.newInputStream(archive);
                ZipInputStream zip = new ZipInputStream(stream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = into.resolve(entry.getName()).normalize();
                if (!target.startsWith(into)) {
                    // Zip Slip : une entrée « ../../bin/quelque-chose » ne sortira pas du bac.
                    throw new IOException("entrée d'archive hors du dossier de décompression");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    /** L'exécutable quelque part dans l'arborescence décompressée. */
    private Path locate(LocalTool tool, Path root) throws IOException {
        List<String> wanted = new ArrayList<>();
        for (String executable : tool.executables()) {
            wanted.add(executableName(executable).toLowerCase(Locale.ROOT));
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> wanted.contains(
                            path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .min(Comparator.comparingInt(Path::getNameCount))
                    .orElse(null);
        }
    }

    // ------------------------------------------------------------------ vérification

    /**
     * Ce que nous vérifions vraiment : <b>le binaire s'identifie</b>. Pas une empreinte épinglée —
     * ces distributions sont roulantes, et une empreinte figée ferait échouer le rapatriement dès la
     * première mise à jour amont. C'est écrit tel quel plutôt que maquillé.
     */
    private void verify(LocalTool tool, Path executable) {
        try {
            ProcessRunner.ProcessResult result = processes.run(
                    List.of(executable.toAbsolutePath().toString(), "-version"), null,
                    VERIFY_TIMEOUT_MS);
            String banner = (String.join(" ", result.stdout()) + " " + String.join(" ",
                    result.stderr())).toLowerCase(Locale.ROOT);
            if (!banner.contains(tool.name().toLowerCase(Locale.ROOT))) {
                throw new ToolchainUnavailableException(
                        "Le binaire trouvé pour « " + tool.name() + " » ne s'identifie pas comme "
                                + "tel : je ne m'en sers pas.",
                        "Installez-le vous-même : " + tool.installAdvice(os) + ".");
            }
        } catch (IOException e) {
            throw new ToolchainUnavailableException(
                    "« " + tool.name() + " » n'a pas pu être lancé sur cette machine.",
                    "Installez-le vous-même : " + tool.installAdvice(os) + ".", e);
        }
    }

    // ------------------------------------------------------------------ plomberie

    private String executableName(String base) {
        return os == OperatingSystem.WINDOWS ? base + ".exe" : base;
    }

    private static void makeExecutable(Path path) {
        try {
            path.toFile().setExecutable(true, false);
        } catch (RuntimeException e) {
            // Windows n'a pas de bit d'exécution : l'absence de geste n'est pas une erreur.
        }
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return "la source officielle";
        }
    }

    private static String mib(long bytes) {
        return String.format(Locale.FRENCH, "%.1f Mo", bytes / (1024.0 * 1024.0));
    }

    private static String reason(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message.strip();
    }

    private static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException ignored) {
                    // Un reste dans un dossier temporaire n'est pas un état corrompu.
                }
            });
        } catch (IOException ignored) {
            // Idem.
        }
    }

    /** Recherche d'un exécutable dans le {@code PATH}, isolée pour être éprouvable. */
    @FunctionalInterface
    interface PathLookup {
        Path find(String executableName);
    }

    /** Rapatriement d'une archive, isolé pour être éprouvable sans réseau. */
    @FunctionalInterface
    interface Downloader {
        /** @return le nombre d'octets écrits dans {@code into} */
        long fetch(String url, Path into) throws IOException;
    }

    static Path lookupOnPath(String executableName) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String element : path.split(java.io.File.pathSeparator)) {
            if (element.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(element).resolve(executableName);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().normalize();
                }
            } catch (RuntimeException ignored) {
                // Un élément de PATH malformé n'empêche pas de regarder les suivants.
            }
        }
        return null;
    }

    /** Le rapatriement réel : HTTPS uniquement, borné en taille. */
    static Downloader httpDownloader() {
        return (url, into) -> {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("adresse non HTTPS refusée");
            }
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(DOWNLOAD_TIMEOUT_MS))
                    .GET()
                    .build();
            try {
                HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    throw new IOException("réponse HTTP " + response.statusCode());
                }
                try (InputStream body = response.body()) {
                    long written = Files.copy(body, into, StandardCopyOption.REPLACE_EXISTING);
                    if (written > MAX_ARCHIVE_BYTES) {
                        throw new IOException("archive anormalement grosse (" + written + " octets)");
                    }
                    return written;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("téléchargement interrompu", e);
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        };
    }
}
