package fr.claudegateway.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Résout, et retient, le <b>dossier de travail d'un projet</b> sous la racine du poste
 * (F-48 / SF-48-02, revu par F-73 / SF-73-01).
 *
 * <p><b>Ce que cette classe n'est plus.</b> Elle portait le confinement par projet : un agent
 * travaillant sur le projet A ne devait pas atteindre le projet B. Le product owner a retiré ce
 * confinement le 2026-09-12, après vérification qu'il n'existait <b>déjà pas</b> pour {@code bash} —
 * seul le {@code cwd} passait par la garde, jamais la commande. Il ne tenait donc que sur les outils
 * fichiers, et ce qui ne tient qu'à moitié ne tient pas.</p>
 *
 * <p><b>Ce qu'elle fait aujourd'hui.</b> Elle traduit le projet annoncé par la gateway en un
 * <b>dossier de départ</b> : celui où {@code bash} démarre, celui d'où part le balayage de
 * {@code list_files}, celui sous lequel se résolvent les chemins relatifs. La normalisation du
 * chemin de projet est conservée — relatif, sans {@code ..}, sans lettre de lecteur — non plus
 * comme une garantie de sécurité, mais comme un contrôle de <b>validité</b> : une valeur malformée
 * ferait démarrer le tour ailleurs, en silence, et c'est exactement ce qu'on ne veut pas.</p>
 *
 * <p><b>Exclusions par projet</b> : {@code .runnerignore} est lu <b>dans le dossier du projet</b>.
 * C'est un fichier de projet, et c'est là que l'utilisateur l'écrit. Il n'élague plus que le
 * <b>listage</b> (F-73) : plus aucune liste de secrets non désactivable.</p>
 *
 * <p>Le résultat est <b>retenu par projet</b> : une arborescence ne se résout pas à chaque appel, et
 * les exclusions ne se relisent pas à chaque outil.</p>
 */
public final class ProjectScopes implements ToolScopes {

    /** Longueur maximale d'un chemin de projet accepté (même borne que {@link PathResolver}). */
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
        PathResolver paths = new PathResolver(folder, exclusions);
        ToolRouter router = new ToolRouter(new FileTools(paths), new BashTool(paths, allowBash, shell));
        ToolRouter raced = byProject.putIfAbsent(relative, router);
        return raced == null ? router : raced;
    }

    /**
     * Dossier réel du projet, résolu sous la racine du poste. C'est un contrôle de <b>validité</b>
     * de la valeur reçue, pas une borne posée sur ce que le tour pourra atteindre (F-73).
     *
     * @throws ToolException {@code not_found} si le dossier n'existe pas ou n'en est pas un,
     *                       {@code path_outside_root} si la valeur reçue sort de la racine du poste
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
            // Canonicalisation : un dossier de départ doit être un vrai dossier, lisible maintenant.
            // Ce qu'il pointe après résolution des liens n'est plus refusé (F-73).
            real = candidate.toRealPath();
        } catch (IOException e) {
            throw new ToolException("io_error", "Dossier de projet illisible : " + relative);
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
