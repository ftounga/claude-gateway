package fr.claudegateway.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Résout, et retient, le <b>confinement d'un projet</b> sous la racine du poste
 * (F-48 / SF-48-02).
 *
 * <p><b>Le point dur de tout le chantier.</b> Depuis SF-48-01, le runner est appairé à une machine et
 * lancé à sa racine (par exemple {@code ~/dev}) ; les projets sont des sous-dossiers. Si la garde
 * restait celle de la racine, un agent travaillant sur le projet A pourrait lire — voire écrire —
 * dans le projet B. Cette classe est ce qui l'en empêche : elle fabrique un {@link PathGuard} par
 * projet, et c'est celui-là que les outils reçoivent.</p>
 *
 * <p><b>Régime local, décision non réversible du cadrage.</b> La gateway <i>indique</i> le projet du
 * tour ; c'est ce processus qui <i>refuse</i> d'en sortir. Déplacer la garantie dans la gateway
 * ferait dépendre la promesse centrale du mode runner d'un composant réseau — un défaut côté serveur
 * ouvrirait alors toute la racine. Ici, un runner compromis côté réseau ne peut toujours pas sortir
 * du dossier qu'on lui a désigné.</p>
 *
 * <p><b>Deux vérifications, dans cet ordre</b> : la forme du chemin (relatif, sans {@code ..}, sans
 * lettre de lecteur) <b>puis</b> sa canonicalisation ({@link Path#toRealPath}) — un lien symbolique
 * du projet A vers le projet B est donc refusé, exactement comme le fait déjà {@link PathGuard} pour
 * les fichiers.</p>
 *
 * <p><b>Exclusions par projet</b> : {@code .runnerignore} est lu <b>dans le dossier du projet</b>.
 * C'est un fichier de projet, et c'est là que l'utilisateur l'écrit. La liste par défaut non
 * désactivable (D10) s'applique partout, inchangée.</p>
 *
 * <p>Le résultat est <b>retenu par projet</b> : une arborescence ne se canonicalise pas à chaque
 * appel, et les exclusions ne se relisent pas à chaque outil.</p>
 */
public final class ProjectScopes implements ToolScopes {

    /** Longueur maximale d'un chemin de projet accepté (même borne que {@link PathGuard}). */
    static final int MAX_PROJECT_LENGTH = 4096;

    private static final String OUTSIDE_ROOT = "path_outside_root";

    private final Path hostRoot;
    private final boolean allowBash;
    private final ShellElection shell;
    private final Console console;
    private final Map<String, ToolRouter> byProject = new ConcurrentHashMap<>();

    /**
     * @param hostRoot  racine <b>du poste</b>, déjà validée par {@link RunnerConfig}
     * @param allowBash exécution de commandes autorisée sur cette machine (SF-38-19)
     * @param shell     interpréteur élu au démarrage (SF-38-27) — élu une fois, pour tous les projets
     * @param console   journal de démarrage ; sert à annoncer les exclusions du premier projet ouvert
     * @throws ToolException {@code io_error} si la racine du poste est illisible
     */
    public ProjectScopes(Path hostRoot, boolean allowBash, ShellElection shell, Console console) {
        try {
            this.hostRoot = hostRoot.toRealPath();
        } catch (IOException e) {
            throw new ToolException("io_error", "Racine du poste illisible.");
        }
        this.allowBash = allowBash;
        this.shell = shell;
        this.console = console;
    }

    /** Racine canonique du poste — jamais celle d'un projet. */
    public Path hostRoot() {
        return hostRoot;
    }

    @Override
    public ToolExecutor forProject(String project) {
        return routerFor(project);
    }

    /**
     * Capacités annoncées dans la trame {@code ready} (contrat §2.1). Elles décrivent la
     * <b>machine</b> — {@code files} toujours, {@code bash} sauf {@code --no-bash} — et ne dépendent
     * donc d'aucun projet.
     */
    public List<String> capabilities() {
        return routerFor("").capabilities();
    }

    private ToolRouter routerFor(String project) {
        String relative = normalize(project);
        ToolRouter cached = byProject.get(relative);
        if (cached != null) {
            return cached;
        }
        Path folder = resolveFolder(relative);
        ExclusionRules exclusions = ExclusionRules.load(folder, console);
        PathGuard guard = new PathGuard(folder, exclusions);
        ToolRouter router = new ToolRouter(new FileTools(guard), new BashTool(guard, allowBash, shell));
        ToolRouter raced = byProject.putIfAbsent(relative, router);
        return raced == null ? router : raced;
    }

    /**
     * Dossier réel du projet, canonicalisé et vérifié sous la racine du poste.
     *
     * @throws ToolException {@code not_found} si le dossier n'existe pas ou n'en est pas un,
     *                       {@code path_outside_root} s'il sort de la racine
     */
    private Path resolveFolder(String relative) {
        if (relative.isEmpty()) {
            return hostRoot;
        }
        Path candidate = hostRoot.resolve(relative).normalize();
        if (!candidate.startsWith(hostRoot)) {
            throw outside(relative);
        }
        if (!Files.isDirectory(candidate)) {
            // Le message ne cite QUE le chemin relatif : l'arborescence de la machine ne remonte
            // jamais à la gateway (garde de SF-38-04, conservée telle quelle).
            throw new ToolException("not_found", "Dossier de projet introuvable : " + relative);
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            throw new ToolException("io_error", "Dossier de projet illisible : " + relative);
        }
        // Après résolution des liens : un lien du projet A vers le projet B ne donne pas accès à B.
        if (!real.startsWith(hostRoot)) {
            throw outside(relative);
        }
        return real;
    }

    /**
     * Forme canonique du chemin de projet : séparateurs {@code /}, segments vides et {@code .}
     * supprimés. Refuse d'emblée les formes qui sortent de la racine par construction. La chaîne
     * <b>vide</b> est légitime : elle désigne la racine du poste.
     */
    static String normalize(String rawProject) {
        if (rawProject == null) {
            return "";
        }
        if (rawProject.length() > MAX_PROJECT_LENGTH) {
            throw new ToolException("invalid_input", "Chemin de projet trop long.");
        }
        if (rawProject.indexOf('\0') >= 0) {
            throw new ToolException("invalid_input", "Chemin de projet invalide.");
        }
        String path = rawProject.trim().replace('\\', '/');
        if (path.isEmpty()) {
            return "";
        }
        if (path.startsWith("/")) {
            throw outside(path);
        }
        if (path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0))) {
            throw outside(path);
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
        return normalized.toString();
    }

    private static ToolException outside(String relative) {
        return new ToolException(OUTSIDE_ROOT, "Projet hors de la racine du poste : " + relative);
    }
}
