package fr.claudegateway.runner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Confinement des chemins à la racine {@code --workspace} (F-38 / SF-38-04, décision D6 : la
 * vérification qui fait foi est celle du runner).
 *
 * <p>Un chemin reçu de la gateway est toujours <b>relatif</b>. Il est refusé s'il est absolu, s'il
 * porte une lettre de lecteur Windows, s'il contient {@code ..} ou un octet nul. Après résolution, la
 * cible est <b>canonicalisée</b> ({@link Path#toRealPath}) — liens symboliques compris — et doit
 * rester sous la racine canonique, sans quoi l'accès est refusé avec le code
 * {@code path_outside_root}. Pour un fichier qui n'existe pas encore (écriture), c'est le plus
 * profond ancêtre existant qui est canonicalisé : les segments manquants ne peuvent pas être des
 * liens.</p>
 *
 * <p>Les messages d'erreur ne citent que le chemin <b>relatif</b> demandé : le chemin absolu de la
 * machine ne remonte jamais à la gateway.</p>
 *
 * <p>Depuis SF-38-10, cette même garde porte aussi le <b>filtre d'exclusion</b>
 * ({@link ExclusionRules}) : tout chemin exclu est refusé avec le code {@code excluded}, avant
 * la moindre ouverture de fichier. C'est le point de passage unique des outils adressant un chemin
 * ({@code read_file}, {@code write_file}) ; le balayage ({@code list_files}, {@code search_files})
 * consulte le même objet via {@link #exclusions()}.</p>
 */
public final class PathGuard {

    /** Longueur maximale d'un chemin accepté (garde-fou d'entrée). */
    public static final int MAX_PATH_LENGTH = 4096;

    private static final String OUTSIDE_ROOT = "path_outside_root";

    private final Path root;
    private final ExclusionRules exclusions;

    /**
     * Racine sans fichier de règles : seule la liste par défaut non désactivable (D10) s'applique.
     *
     * @param root racine à exposer ; canonicalisée une fois pour toutes
     * @throws ToolException {@code io_error} si la racine est illisible
     */
    public PathGuard(Path root) {
        this(root, ExclusionRules.defaultsOnly());
    }

    /**
     * @param root       racine à exposer ; canonicalisée une fois pour toutes
     * @param exclusions filtre d'exclusion appliqué à tout chemin résolu (SF-38-10)
     * @throws ToolException {@code io_error} si la racine est illisible
     */
    public PathGuard(Path root, ExclusionRules exclusions) {
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new ToolException("io_error", "Racine du runner illisible.");
        }
        this.exclusions = exclusions;
    }

    /** Racine canonique exposée par le runner. */
    public Path root() {
        return root;
    }

    /** Filtre d'exclusion partagé par tous les outils (SF-38-10). */
    public ExclusionRules exclusions() {
        return exclusions;
    }

    /**
     * Normalise puis résout un chemin relatif reçu de la gateway.
     *
     * <p>Le confinement à la racine est vérifié <b>avant</b> l'exclusion : un chemin qui sort de la
     * racine reste un {@code path_outside_root}, jamais un {@code excluded}.</p>
     *
     * @throws ToolException {@code invalid_input} si le chemin est vide/malformé,
     *                       {@code path_outside_root} s'il sort de la racine,
     *                       {@code excluded} s'il est filtré par {@link ExclusionRules}
     */
    public Resolved resolve(String rawPath) {
        String relative = normalize(rawPath);
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw outside(relative);
        }
        Path existing = candidate;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            if (existing == null) {
                throw outside(relative);
            }
        }
        Path real;
        try {
            real = existing.toRealPath();
        } catch (IOException e) {
            throw new ToolException("io_error", "Chemin illisible : " + relative);
        }
        if (!real.startsWith(root)) {
            throw outside(relative);
        }
        if (exclusions.isExcluded(relative, Files.isDirectory(candidate))) {
            throw new ToolException("excluded", "Chemin exclu par la configuration du runner : " + relative);
        }
        return new Resolved(relative, candidate);
    }

    /** Chemin relatif à la racine, séparateur {@code /} — la forme qui sort du runner. */
    public String relativize(Path path) {
        String relative = root.relativize(path).toString();
        return File.separatorChar == '/' ? relative : relative.replace(File.separatorChar, '/');
    }

    private static ToolException outside(String relative) {
        return new ToolException(OUTSIDE_ROOT, "Chemin hors de la racine du runner : " + relative);
    }

    /**
     * Forme canonique relative : séparateurs {@code /}, segments vides et {@code .} supprimés.
     * Refuse d'emblée les formes qui sortent de la racine par construction.
     */
    private static String normalize(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new ToolException("invalid_input", "Chemin vide.");
        }
        if (rawPath.length() > MAX_PATH_LENGTH) {
            throw new ToolException("invalid_input", "Chemin trop long.");
        }
        if (rawPath.indexOf('\0') >= 0) {
            throw new ToolException("invalid_input", "Chemin invalide.");
        }
        String path = rawPath.trim().replace('\\', '/');
        if (path.startsWith("/")) {
            throw outside(rawPath.trim());
        }
        if (path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0))) {
            throw outside(rawPath.trim());
        }
        StringBuilder normalized = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw outside(path);
            }
            if (!normalized.isEmpty()) {
                normalized.append('/');
            }
            normalized.append(segment);
        }
        if (normalized.isEmpty()) {
            throw new ToolException("invalid_input", "Chemin vide.");
        }
        return normalized.toString();
    }

    /**
     * Chemin validé.
     *
     * @param relative forme relative canonique (celle qui apparaît dans les messages)
     * @param path     chemin absolu confiné à la racine
     */
    public record Resolved(String relative, Path path) {
    }
}
