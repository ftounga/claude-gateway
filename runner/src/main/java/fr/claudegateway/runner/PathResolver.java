package fr.claudegateway.runner;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Résolution des chemins reçus de la gateway (F-73 / SF-73-01). <b>Ce n'est pas une garde</b> : elle
 * ne confine rien, et son nom le dit.
 *
 * <p>Elle a remplacé {@code PathGuard}, qui refusait tout chemin sortant de la racine exposée. Ce
 * confinement a été retiré partout par décision du product owner du 2026-09-12, pour une raison
 * vérifiée dans le code : il <b>n'existait déjà pas</b> pour {@code bash}. Le {@code cwd} d'une
 * commande passait bien par la garde, mais la commande elle-même n'était jamais inspectée — un
 * {@code cat ../autre-client/.env} s'exécutait sans obstacle. Et un shell ne se confine pas par
 * inspection de texte : seule une mise en conteneur le ferait, au prix de l'usage même du produit
 * (travailler sur la machine du client, avec ses outils). Plutôt que de tenir une promesse
 * partielle sur les seuls outils fichiers, on la retire — et l'application le <b>dit</b>
 * (StartupDisclosure, et l'invite d'autorisation côté écran).</p>
 *
 * <p>Ce qui subsiste ici est de la <b>résolution</b> et des <b>bornes de forme</b> :</p>
 * <ul>
 *   <li>un chemin <b>relatif</b> se résout sous le dossier du projet — c'est ce que le modèle écrit
 *       le plus souvent, et cela reste le point de départ commode ;</li>
 *   <li>un chemin <b>absolu</b> est pris tel quel ; {@code ~} est étendu au dossier du compte ;</li>
 *   <li>{@code ..} est accepté ;</li>
 *   <li>un chemin vide, de plus de {@value #MAX_PATH_LENGTH} caractères, ou porteur d'un octet nul
 *       est refusé en {@code invalid_input} — garde-fou d'entrée, pas confinement.</li>
 * </ul>
 *
 * <p>Les {@link ExclusionRules} restent portées ici, mais n'ont plus qu'un usage : élaguer le
 * <b>balayage</b> de {@code list_files} et {@code search_files} (bruit de construction et
 * {@code .runnerignore}). Un chemin <b>adressé</b> n'est plus jamais refusé pour cause d'exclusion.</p>
 */
public final class PathResolver {

    /** Longueur maximale d'un chemin accepté (garde-fou d'entrée). */
    public static final int MAX_PATH_LENGTH = 4096;

    private final Path root;
    private final ExclusionRules exclusions;

    /**
     * Racine sans fichier de règles : aucun filtre utilisateur, seul le bruit de construction est
     * écarté du balayage.
     *
     * @param root dossier de départ des chemins relatifs
     */
    public PathResolver(Path root) {
        this(root, ExclusionRules.noiseOnly());
    }

    /**
     * @param root       dossier de départ des chemins relatifs (le projet du tour)
     * @param exclusions filtre de <b>listage</b> ; n'est jamais appliqué à un chemin adressé
     */
    public PathResolver(Path root, ExclusionRules exclusions) {
        this.root = root.toAbsolutePath().normalize();
        this.exclusions = exclusions;
    }

    /** Dossier de départ des chemins relatifs, et racine du balayage de {@code list_files}. */
    public Path root() {
        return root;
    }

    /** Filtre de listage partagé par {@code list_files} et {@code search_files}. */
    public ExclusionRules exclusions() {
        return exclusions;
    }

    /**
     * Normalise puis résout un chemin reçu de la gateway. Ne refuse plus aucun emplacement : seules
     * la forme et la longueur sont vérifiées.
     *
     * @throws ToolException {@code invalid_input} si le chemin est vide, trop long ou malformé
     */
    public Resolved resolve(String rawPath) {
        String display = normalize(rawPath);
        Path expanded = expandHome(display);
        Path candidate = expanded.isAbsolute()
                ? expanded.normalize()
                : root.resolve(expanded).normalize();
        return new Resolved(display, candidate);
    }

    /** Chemin relatif à la racine quand il s'y trouve, sinon le chemin absolu, séparateur {@code /}. */
    public String relativize(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        String text = absolute.startsWith(root)
                ? root.relativize(absolute).toString()
                : absolute.toString();
        return File.separatorChar == '/' ? text : text.replace(File.separatorChar, '/');
    }

    /**
     * {@code ~} et {@code ~/…} désignent le dossier du compte qui a lancé le runner. Un
     * {@code ~autre} (dossier d'un autre compte, forme shell) n'est pas interprété : il reste un nom
     * de dossier ordinaire, et l'OS tranchera.
     */
    private static Path expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/")) {
            String home = System.getProperty("user.home", "");
            if (!home.isEmpty()) {
                return path.length() <= 2 ? Paths.get(home) : Paths.get(home, path.substring(2));
            }
        }
        return Paths.get(path);
    }

    /**
     * Forme canonique d'affichage : séparateurs {@code /}, segments vides et {@code .} superflus
     * supprimés. Le résultat est ce qui apparaît dans les messages d'erreur — la forme que
     * l'appelant a demandée, ni plus (on n'ajoute pas l'arborescence de la machine) ni moins.
     */
    static String normalize(String rawPath) {
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
        boolean rooted = path.startsWith("/");
        // Lettre de lecteur Windows (« C:/… ») : conservée telle quelle, c'est un chemin absolu.
        boolean drive = path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0));
        StringBuilder normalized = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if (!normalized.isEmpty()) {
                normalized.append('/');
            }
            normalized.append(segment);
        }
        if (normalized.isEmpty()) {
            if (rooted) {
                return "/";
            }
            throw new ToolException("invalid_input", "Chemin vide.");
        }
        if (drive) {
            return normalized.toString();
        }
        return rooted ? "/" + normalized : normalized.toString();
    }

    /**
     * Chemin résolu.
     *
     * @param display forme demandée, normalisée — celle qui apparaît dans les messages
     * @param path    chemin absolu sur lequel l'outil va travailler
     */
    public record Resolved(String display, Path path) {
    }
}
