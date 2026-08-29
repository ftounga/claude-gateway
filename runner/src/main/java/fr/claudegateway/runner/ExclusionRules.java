package fr.claudegateway.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Filtre d'exclusion appliqué <b>sur la machine de l'utilisateur</b> (F-38 / SF-38-10, décision
 * D10) : ce qui est exclu ne quitte jamais la machine.
 *
 * <p>Deux jeux de règles, évalués dans cet ordre :</p>
 * <ol>
 *   <li>les <b>règles utilisateur</b>, lues dans {@code .runnerignore} à la racine
 *       {@code --workspace} — à défaut, <b>repli</b> sur {@code .gitignore} ; syntaxe gitignore, la
 *       <b>dernière règle qui correspond l'emporte</b> (négation {@code !} comprise) ;</li>
 *   <li>la <b>liste par défaut non désactivable</b> ({@link #DEFAULT_DENY}), évaluée <b>en dernier</b>
 *       et qui <b>gagne toujours</b> : une négation utilisateur ({@code !.env}) ne la réactive
 *       jamais.</li>
 * </ol>
 *
 * <p>Les motifs se résolvent <b>relativement à la racine</b>, sur un chemin déjà normalisé par
 * {@link PathGuard} (séparateur {@code /}, sans {@code ..}, sans {@code /} initial). Un chemin est
 * exclu dès que lui-même <b>ou l'un de ses dossiers ancêtres</b> l'est — comme git, aucune négation
 * ne réactive un fichier situé sous un dossier exclu.</p>
 *
 * <p><b>Aucun motif « fichiers cachés »</b> n'est ajouté à la liste par défaut : un motif du type
 * {@code .*} exclurait {@code .claude/skills/**}, que la construction du prompt système lit pour
 * amorcer l'agent, et les conventions du projet disparaîtraient en silence.</p>
 */
public final class ExclusionRules {

    /**
     * Liste par défaut <b>non désactivable</b> (D10). Volontairement littérale et courte : elle est
     * évaluée en dernier et ne peut pas être neutralisée par {@code .runnerignore}.
     */
    public static final List<String> DEFAULT_DENY =
            List.of(".env", "*.pem", "id_rsa*", ".aws/", ".kube/config", ".ssh/");

    /** Nom du fichier de règles propre au runner. */
    public static final String RUNNER_IGNORE = ".runnerignore";

    /** Fichier de repli, utilisé uniquement si {@link #RUNNER_IGNORE} est absent. */
    public static final String GIT_IGNORE = ".gitignore";

    /** Au-delà, le fichier de règles n'est pas lu (garde-fou d'entrée). */
    static final long MAX_RULES_FILE_BYTES = 1024L * 1024;

    /** Nombre maximal de règles retenues ; les lignes suivantes sont ignorées. */
    static final int MAX_RULES = 5_000;

    /** Longueur maximale d'une ligne de règle ; au-delà, la ligne est ignorée. */
    static final int MAX_RULE_LENGTH = 1_000;

    private final List<Rule> userRules;
    private final List<Rule> denyRules;
    private final String source;

    private ExclusionRules(List<Rule> userRules, List<Rule> denyRules, String source) {
        this.userRules = userRules;
        this.denyRules = denyRules;
        this.source = source;
    }

    /**
     * Charge les règles d'une racine : {@code .runnerignore} s'il existe, sinon {@code .gitignore},
     * sinon la seule liste par défaut. Ne lève jamais : un fichier illisible produit un
     * avertissement et un repli sur la liste par défaut.
     *
     * @param root    racine {@code --workspace}
     * @param console sortie d'avertissement, éventuellement {@code null}
     */
    public static ExclusionRules load(Path root, Console console) {
        List<Rule> deny = compileAll(DEFAULT_DENY, true, null);
        Path runnerIgnore = root.resolve(RUNNER_IGNORE);
        Path gitIgnore = root.resolve(GIT_IGNORE);
        Path file;
        String source;
        if (isReadableFile(runnerIgnore)) {
            file = runnerIgnore;
            source = RUNNER_IGNORE;
        } else if (isReadableFile(gitIgnore)) {
            file = gitIgnore;
            source = GIT_IGNORE;
        } else {
            return new ExclusionRules(List.of(), deny, "(aucun)");
        }
        List<String> lines = readLines(file, source, console);
        return new ExclusionRules(compileAll(lines, false, console), deny, source);
    }

    /** Règles par défaut seules — utile aux tests et à tout appel sans fichier de règles. */
    public static ExclusionRules defaultsOnly() {
        return new ExclusionRules(List.of(), compileAll(DEFAULT_DENY, true, null), "(aucun)");
    }

    /** Règles par défaut plus des règles utilisateur fournies en mémoire (tests). */
    static ExclusionRules of(List<String> userPatterns) {
        return new ExclusionRules(compileAll(userPatterns, false, null),
                compileAll(DEFAULT_DENY, true, null), "(mémoire)");
    }

    /** Origine des règles utilisateur : {@code .runnerignore}, {@code .gitignore} ou {@code (aucun)}. */
    public String source() {
        return source;
    }

    /** Nombre de règles utilisateur retenues (les règles par défaut ne sont pas comptées). */
    public int userRuleCount() {
        return userRules.size();
    }

    /**
     * Vrai si le chemin relatif est exclu, lui-même ou par l'un de ses dossiers ancêtres.
     *
     * @param relativePath chemin relatif normalisé (séparateur {@code /}, sans {@code /} initial)
     * @param directory    vrai si le chemin désigne un dossier
     */
    public boolean isExcluded(String relativePath, boolean directory) {
        if (relativePath == null || relativePath.isEmpty()) {
            return false;
        }
        String path = relativePath.replace('\\', '/');
        // Chaque ancêtre est testé en tant que dossier : un dossier exclu emporte tout son contenu.
        int slash = path.indexOf('/');
        while (slash >= 0) {
            if (matches(path.substring(0, slash), true)) {
                return true;
            }
            slash = path.indexOf('/', slash + 1);
        }
        return matches(path, directory);
    }

    /** Raccourci pour un fichier. */
    public boolean isExcludedFile(String relativePath) {
        return isExcluded(relativePath, false);
    }

    /** Raccourci pour un dossier. */
    public boolean isExcludedDirectory(String relativePath) {
        return isExcluded(relativePath, true);
    }

    /**
     * Verdict pour un chemin donné, sans remonter aux ancêtres : dernière règle utilisateur qui
     * correspond, puis liste par défaut qui écrase toujours le verdict.
     */
    private boolean matches(String path, boolean directory) {
        boolean excluded = false;
        for (Rule rule : userRules) {
            if (rule.matches(path, directory)) {
                excluded = !rule.negated();
            }
        }
        for (Rule rule : denyRules) {
            if (rule.matches(path, directory)) {
                return true; // non désactivable : gagne toujours (D10)
            }
        }
        return excluded;
    }

    private static boolean isReadableFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(path);
    }

    private static List<String> readLines(Path file, String source, Console console) {
        try {
            if (Files.size(file) > MAX_RULES_FILE_BYTES) {
                warn(console, "Fichier " + source + " trop volumineux : règles utilisateur ignorées.");
                return List.of();
            }
            // Décodage tolérant : un octet non UTF-8 ne doit pas faire perdre tout le fichier.
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return List.of(text.split("\r?\n", -1));
        } catch (IOException | RuntimeException e) {
            warn(console, "Fichier " + source + " illisible : seules les exclusions par défaut s'appliquent.");
            return List.of();
        }
    }

    private static List<Rule> compileAll(List<String> patterns, boolean mandatory, Console console) {
        List<Rule> rules = new ArrayList<>();
        int ignored = 0;
        for (String raw : patterns) {
            if (rules.size() >= MAX_RULES) {
                warn(console, "Plus de " + MAX_RULES + " règles d'exclusion : les suivantes sont ignorées.");
                break;
            }
            Rule rule = Rule.compile(raw, mandatory);
            if (rule == null) {
                if (raw != null && !raw.isBlank() && !raw.strip().startsWith("#")) {
                    ignored++;
                }
                continue;
            }
            rules.add(rule);
        }
        if (ignored > 0) {
            warn(console, ignored + " règle(s) d'exclusion ignorée(s) (motif inexploitable).");
        }
        return List.copyOf(rules);
    }

    private static void warn(Console console, String message) {
        if (console != null) {
            console.warn(message);
        }
    }

    /**
     * Une règle compilée. {@code negated} n'est jamais vrai pour une règle de la liste par défaut :
     * la négation est une notion purement utilisateur.
     */
    private record Rule(Pattern pattern, boolean negated, boolean directoryOnly) {

        boolean matches(String path, boolean directory) {
            if (directoryOnly && !directory) {
                return false;
            }
            return pattern.matcher(path).matches();
        }

        /**
         * Compile une ligne de syntaxe gitignore, ou renvoie {@code null} si elle n'est pas une
         * règle (ligne vide, commentaire, motif inexploitable).
         *
         * @param mandatory règle de la liste par défaut : la négation est refusée et le motif est
         *                  comparé à <b>n'importe quelle profondeur</b>, y compris s'il contient un
         *                  {@code /} (un {@code projet/.kube/config} est exclu comme celui de la racine)
         */
        static Rule compile(String rawLine, boolean mandatory) {
            if (rawLine == null) {
                return null;
            }
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || line.length() > MAX_RULE_LENGTH) {
                return null;
            }
            boolean negated = false;
            if (line.startsWith("!")) {
                if (mandatory) {
                    return null; // une entrée non désactivable ne peut pas être une négation
                }
                negated = true;
                line = line.substring(1).strip();
            }
            line = line.replace('\\', '/');
            boolean directoryOnly = line.endsWith("/");
            while (line.endsWith("/")) {
                line = line.substring(0, line.length() - 1);
            }
            // Ancrage : un motif qui commence par « / » ou qui contient un « / » interne vise un
            // chemin depuis la racine ; sinon il vise un nom de base à n'importe quelle profondeur.
            // La liste par défaut n'est jamais ancrée : elle doit mordre quelle que soit la
            // profondeur (un « projet/.kube/config » vaut celui de la racine).
            boolean anchored = line.startsWith("/") || line.contains("/");
            while (line.startsWith("/")) {
                line = line.substring(1);
            }
            if (line.isEmpty()) {
                return null;
            }
            if (mandatory) {
                anchored = false;
            }
            String regex = (anchored ? "" : "(?:.*/)?") + toRegex(line);
            try {
                return new Rule(Pattern.compile(regex), negated, directoryOnly);
            } catch (PatternSyntaxException e) {
                return null;
            }
        }

        /** Traduction glob → regex : {@code **} traverse les segments, {@code *} et {@code ?} non. */
        private static String toRegex(String glob) {
            StringBuilder regex = new StringBuilder();
            int i = 0;
            while (i < glob.length()) {
                char c = glob.charAt(i);
                switch (c) {
                    case '*' -> {
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                            i++;
                            while (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                                i++;
                            }
                            if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                                i++;
                                regex.append("(?:.*/)?"); // « **/ » : zéro ou plusieurs segments
                            } else {
                                regex.append(".*");
                            }
                        } else {
                            regex.append("[^/]*");
                        }
                    }
                    case '?' -> regex.append("[^/]");
                    default -> regex.append(Pattern.quote(String.valueOf(c)));
                }
                i++;
            }
            return regex.toString();
        }
    }
}
