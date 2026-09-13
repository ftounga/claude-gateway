package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>Les dossiers OneDrive / SharePoint synchronisés sur la machine</b> (F-108 / SF-108-03, cadrage
 * §5.1) — et la raison pour laquelle, souvent, aucun geste n'est nécessaire.
 *
 * <p>« Préférer le local quand il existe » : si la bibliothèque d'une équipe est synchronisée par le
 * client OneDrive, ses fichiers sont <b>déjà</b> sur le disque, et les outils du poste font le
 * travail sans toucher au navigateur. Cette classe reconnaît ces racines et traduit un emplacement
 * SharePoint en chemin local <b>s'il existe</b> — jamais un chemin supposé.</p>
 *
 * <ul>
 *   <li><b>Windows</b> : {@code %OneDriveCommercial%} ou {@code %USERPROFILE%\OneDrive - <organisation>}
 *       pour OneDrive professionnel ; {@code %USERPROFILE%\<organisation>\<Site> - <Bibliothèque>}
 *       pour les bibliothèques synchronisées.</li>
 *   <li><b>macOS</b> : {@code ~/Library/CloudStorage/OneDrive-<organisation>} et
 *       {@code ~/Library/CloudStorage/OneDrive-SharedLibraries-<organisation>/<Site> - <Bibliothèque>}.</li>
 * </ul>
 *
 * <p><b>Forme éprouvée sur documentation, à confirmer sur poste réel</b> : ce sont les conventions
 * de nommage publiées du client OneDrive ; un nom de site affiché différent de son adresse peut ne
 * pas être reconnu — l'outil prend alors le chemin du navigateur, et le dit.</p>
 */
public final class SyncedLibraries {

    /** Noms, compactés, de la bibliothèque par défaut d'un site selon la langue. */
    private static final Set<String> DEFAULT_LIBRARY = Set.of("shareddocuments", "documents",
            "documentspartages", "freigegebenedokumente", "documentoscompartidos");

    private final Path home;
    private final Map<String, String> env;
    private final OperatingSystem os;

    public SyncedLibraries(Path home, Map<String, String> env, OperatingSystem os) {
        this.home = home;
        this.env = env == null ? Map.of() : env;
        this.os = os == null ? OperatingSystem.OTHER : os;
    }

    /** Les racines de cette machine. */
    public static SyncedLibraries detect() {
        return new SyncedLibraries(Path.of(System.getProperty("user.home", ".")), System.getenv(),
                OperatingSystem.current());
    }

    /**
     * Le chemin local de cet emplacement, <b>s'il existe sur le disque</b>.
     */
    public Optional<Path> resolve(SharePointLocation location) {
        if (location == null) {
            return Optional.empty();
        }
        List<String> under = location.underSite();
        if (under.isEmpty() || under.stream().anyMatch(s -> ".".equals(s) || "..".equals(s))) {
            return Optional.empty();
        }
        List<String> rest = under.subList(1, under.size());
        if (location.isOneDrive()) {
            if (!"documents".equals(compact(under.get(0)))) {
                return Optional.empty();
            }
            for (Path root : oneDriveRoots()) {
                Optional<Path> found = existing(root, rest);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        List<String> sitePath = segments(location.sitePath());
        String site = sitePath.size() >= 2 ? sitePath.get(1) : "";
        for (Path librariesRoot : libraryRoots()) {
            for (Path library : children(librariesRoot)) {
                if (matchesLibrary(library.getFileName().toString(), site, under.get(0))) {
                    Optional<Path> found = existing(library, rest);
                    if (found.isPresent()) {
                        return found;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Toutes les racines reconnues, pour les dire à l'agent. */
    public List<Path> roots() {
        List<Path> all = new ArrayList<>(oneDriveRoots());
        all.addAll(libraryRoots());
        return all;
    }

    // ------------------------------------------------------------------ racines

    private List<Path> oneDriveRoots() {
        List<Path> roots = new ArrayList<>();
        if (os == OperatingSystem.WINDOWS) {
            addIfDir(roots, env.get("OneDriveCommercial"));
            for (Path child : children(home)) {
                if (child.getFileName().toString().startsWith("OneDrive - ")) {
                    addIfDir(roots, child.toString());
                }
            }
        } else if (os == OperatingSystem.MACOS) {
            for (Path child : children(home.resolve("Library").resolve("CloudStorage"))) {
                String name = child.getFileName().toString();
                if (name.startsWith("OneDrive-") && !name.startsWith("OneDrive-SharedLibraries-")
                        && !"OneDrive-Personal".equals(name)) {
                    addIfDir(roots, child.toString());
                }
            }
        }
        return roots;
    }

    private List<Path> libraryRoots() {
        List<Path> roots = new ArrayList<>();
        if (os == OperatingSystem.WINDOWS) {
            for (Path oneDrive : oneDriveRoots()) {
                String name = oneDrive.getFileName().toString();
                if (name.startsWith("OneDrive - ")) {
                    addIfDir(roots, home.resolve(name.substring("OneDrive - ".length())).toString());
                }
            }
        } else if (os == OperatingSystem.MACOS) {
            for (Path child : children(home.resolve("Library").resolve("CloudStorage"))) {
                if (child.getFileName().toString().startsWith("OneDrive-SharedLibraries-")) {
                    addIfDir(roots, child.toString());
                }
            }
        }
        return roots;
    }

    // ------------------------------------------------------------------ correspondance

    /** « Projet IAM - Documents » correspond au site « ProjetIAM » et à « Shared Documents ». */
    static boolean matchesLibrary(String folderName, String site, String library) {
        int cut = folderName.lastIndexOf(" - ");
        if (cut <= 0) {
            return false;
        }
        String siteTitle = compact(folderName.substring(0, cut));
        String libraryTitle = compact(folderName.substring(cut + 3));
        if (site.isEmpty() || !siteTitle.equals(compact(site))) {
            return false;
        }
        String wanted = compact(library);
        return libraryTitle.equals(wanted)
                || (DEFAULT_LIBRARY.contains(wanted) && DEFAULT_LIBRARY.contains(libraryTitle));
    }

    private static Optional<Path> existing(Path root, List<String> rest) {
        Path candidate = root;
        for (String segment : rest) {
            candidate = candidate.resolve(segment);
        }
        Path normalized = candidate.normalize();
        if (!normalized.startsWith(root.normalize()) || !Files.exists(normalized)) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    private static String compact(String raw) {
        return TeamsTools.fold(raw).replaceAll("[^a-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static List<Path> children(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory).sorted().toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static void addIfDir(List<Path> roots, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        Path path = Path.of(raw.strip());
        if (Files.isDirectory(path) && !roots.contains(path)) {
            roots.add(path);
        }
    }

    private static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isBlank()) {
                out.add(part);
            }
        }
        return out;
    }
}
