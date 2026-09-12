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
 * Filtre de <b>listage</b> appliqué sur la machine de l'utilisateur (F-38 / SF-38-10, puis
 * F-73 / SF-73-01).
 *
 * <p><b>Ce que ce filtre n'est plus.</b> Jusqu'à F-73, il portait aussi une liste de secrets non
 * désactivable ({@code .env}, {@code *.pem}, {@code id_rsa*}, {@code .aws/}, {@code .kube/config},
 * {@code .ssh/}) et refusait tout chemin <b>adressé</b> qui y correspondait. Le product owner l'a
 * retirée le 2026-09-12, en même temps que le confinement : elle ne protégeait que les outils
 * fichiers, alors qu'un simple {@code cat .env} passé à {@code bash} n'a jamais rien rencontré. Une
 * garde qui ne tient que sur la moitié des chemins n'est pas une garde, c'est une phrase. Ce qui
 * remplace la phrase : la porte de confirmation, armée par défaut, et ce que l'application dit.</p>
 *
 * <p><b>Ce qu'il est.</b> Un filtre de <b>lisibilité</b>, appliqué au seul balayage de
 * {@code list_files} et {@code search_files}. Deux jeux de règles, toutes négociables :</p>
 * <ol>
 *   <li>le <b>bruit de construction</b> ({@link #DEFAULT_NOISE}), évalué en premier ;</li>
 *   <li>les <b>règles utilisateur</b>, lues dans {@code .runnerignore} à la racine du projet — à
 *       défaut, <b>repli</b> sur {@code .gitignore} ; syntaxe gitignore, la <b>dernière règle qui
 *       correspond l'emporte</b> (négation {@code !} comprise, y compris sur le bruit).</li>
 * </ol>
 *
 * <p>Les motifs se résolvent <b>relativement au dossier du projet</b>, sur un chemin normalisé
 * (séparateur {@code /}, sans {@code /} initial). Un chemin est exclu dès que lui-même <b>ou l'un de
 * ses dossiers ancêtres</b> l'est — comme git, aucune négation ne réactive un fichier situé sous un
 * dossier exclu.</p>
 *
 * <p><b>Aucun motif « fichiers cachés »</b> : un {@code .*} exclurait {@code .claude/skills/**},
 * que la construction du prompt système lit pour amorcer l'agent.</p>
 */
public final class ExclusionRules {

    /**
     * <b>Bruit de construction</b>, écarté par défaut (F-38 / SF-38-21) : dépendances installées,
     * artefacts de compilation, caches d'outillage.
     *
     * <p>Le banc d'essai a montré pourquoi : un projet fullstack fraîchement construit compte
     * <b>40 590 fichiers</b>, dont 40 112 dans {@code node_modules}. La liste dépassait les deux
     * bornes du listage — 20 000 entrées puis 512 Ko — et l'utilisateur recevait <b>4 829 lignes de
     * dépendances</b> au lieu des 478 fichiers de son projet.</p>
     *
     * <p><b>Négociable</b> : ces motifs sont évalués <b>avant</b> les règles utilisateur, si bien
     * qu'une négation ({@code !node_modules/}) les annule. On écarte du bruit, on ne protège rien —
     * le contournement doit rester possible pour qui sait ce qu'il fait.</p>
     */
    public static final List<String> DEFAULT_NOISE = List.of(
            "node_modules/", "target/", "build/", "dist/", "out/",
            ".angular/", ".next/", ".nuxt/", ".svelte-kit/", ".parcel-cache/",
            ".gradle/", ".venv/", "venv/", "__pycache__/", ".pytest_cache/",
            ".mypy_cache/", ".tox/", "vendor/", "coverage/", ".terraform/");

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

    /**
     * Bruit de construction, évalué <b>avant</b> les règles utilisateur : une négation explicite
     * l'annule. Tenu à part de {@link #userRules} pour que le compteur annoncé sur la console dise
     * le nombre de règles <b>du fichier</b>, et non vingt de plus.
     */
    private final List<Rule> noiseRules;

    private final List<Rule> userRules;
    private final String source;

    private ExclusionRules(List<Rule> userRules, String source) {
        this(compileAll(DEFAULT_NOISE, null), userRules, source);
    }

    private ExclusionRules(List<Rule> noiseRules, List<Rule> userRules, String source) {
        this.noiseRules = noiseRules;
        this.userRules = userRules;
        this.source = source;
    }

    /**
     * Charge les règles d'un dossier de projet : {@code .runnerignore} s'il existe, sinon
     * {@code .gitignore}, sinon le seul bruit de construction. Ne lève jamais : un fichier illisible
     * produit un avertissement et un repli sur le bruit seul.
     *
     * @param root    dossier du projet
     * @param console sortie d'avertissement, éventuellement {@code null}
     */
    public static ExclusionRules load(Path root, Console console) {
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
            // Aucun fichier de règles : le bruit de construction est écarté quand même (SF-38-21).
            return new ExclusionRules(List.of(), "(aucun)");
        }
        List<String> lines = readLines(file, source, console);
        return new ExclusionRules(compileAll(lines, console), source);
    }

    /** Bruit de construction seul — aucun fichier de règles, aucune liste de secrets (F-73). */
    public static ExclusionRules noiseOnly() {
        return new ExclusionRules(List.of(), "(aucun)");
    }

    /** Bruit de construction plus des règles utilisateur fournies en mémoire (tests). */
    static ExclusionRules of(List<String> userPatterns) {
        return new ExclusionRules(compileAll(userPatterns, null), "(mémoire)");
    }

    /**
     * Règles utilisateur <b>sans le bruit par défaut</b> — pour les tests qui exercent la mécanique
     * de correspondance elle-même, où vingt motifs de plus fausseraient la lecture.
     */
    static ExclusionRules ofWithoutNoise(List<String> userPatterns) {
        return new ExclusionRules(List.of(), compileAll(userPatterns, null), "(mémoire)");
    }

    /** Origine des règles utilisateur : {@code .runnerignore}, {@code .gitignore} ou {@code (aucun)}. */
    public String source() {
        return source;
    }

    /** Nombre de règles utilisateur retenues (le bruit de construction n'est pas compté). */
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
     * Verdict pour un chemin donné, sans remonter aux ancêtres : bruit d'abord, règles utilisateur
     * ensuite, <b>la dernière qui correspond l'emporte</b>. Plus aucune règle n'écrase les autres
     * depuis F-73 : tout est négociable, parce que plus rien ici ne prétend protéger.
     */
    private boolean matches(String path, boolean directory) {
        boolean excluded = false;
        for (Rule rule : noiseRules) {
            if (rule.matches(path, directory)) {
                excluded = !rule.negated();
            }
        }
        for (Rule rule : userRules) {
            if (rule.matches(path, directory)) {
                excluded = !rule.negated();
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

    private static List<Rule> compileAll(List<String> patterns, Console console) {
        List<Rule> rules = new ArrayList<>();
        int ignored = 0;
        for (String raw : patterns) {
            if (rules.size() >= MAX_RULES) {
                warn(console, "Plus de " + MAX_RULES + " règles d'exclusion : les suivantes sont ignorées.");
                break;
            }
            Rule rule = Rule.compile(raw);
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

    /** Une règle compilée, négation comprise. */
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
         */
        static Rule compile(String rawLine) {
            if (rawLine == null) {
                return null;
            }
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || line.length() > MAX_RULE_LENGTH) {
                return null;
            }
            boolean negated = false;
            if (line.startsWith("!")) {
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
            boolean anchored = line.startsWith("/") || line.contains("/");
            while (line.startsWith("/")) {
                line = line.substring(1);
            }
            if (line.isEmpty()) {
                return null;
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
