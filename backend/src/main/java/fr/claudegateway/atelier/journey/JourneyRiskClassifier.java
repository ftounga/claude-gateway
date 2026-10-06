package fr.claudegateway.atelier.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>La classe de risque d'un appel d'outil</b> (F-176 / SF-176-04, cadrage §4) : ce que la porte du
 * parcours guidé laisse passer avant un plan validé.
 *
 * <p>Quatre classes : {@code LECTURE} et {@code NOTES} toujours libres ; {@code REVERSIBLE} et
 * {@code EXTERNE} bloquées tant que le plan n'est pas validé (décision Q2 : <i>toute modification hors
 * des notes du sujet</i>, édition en branche comprise).</p>
 *
 * <p><b>Prudence</b> : une commande que la classification ne reconnaît pas comme une lecture est
 * traitée comme une modification. On préfère faire valider un plan pour un {@code cat} exotique que
 * laisser passer un {@code apply} déguisé : la lecture la plus courante (ls, cat, grep, git status,
 * kubectl get, terraform plan, aws describe…) est reconnue.</p>
 *
 * <p>{@code null} = l'appel n'est <b>pas</b> soumis à la porte : il ne touche ni le poste ni un système
 * externe (outils d'organisation de la gateway : plan, attentes, rappel, carte, parcours…).</p>
 */
public final class JourneyRiskClassifier {

    private JourneyRiskClassifier() {
    }

    /** Écritures de fichier dans le projet. */
    private static final Set<String> FILE_WRITES = Set.of("write_file", "edit_file", "multi_edit");

    /** Lectures du projet passant par le poste ou le stockage. */
    private static final Set<String> READ_TOOLS = Set.of("read_file", "list_files", "search_files", "grep",
            "glob", "explore");

    /** Fichiers de notes du sujet (nom de fichier, casse indifférente). */
    private static final Set<String> NOTE_FILES = Set.of("state.md", "plan-action.md", "notes.md", "repo-map.md");

    /** Dossiers de notes (segment de chemin). */
    private static final Set<String> NOTE_DIRS = Set.of("carte", "notes");

    /** Programmes qui ne font que lire (sous réserve des options vérifiées plus bas). */
    private static final Set<String> READ_COMMANDS = Set.of(
            "ls", "ll", "cat", "head", "tail", "less", "more", "grep", "egrep", "fgrep", "rg", "ag", "find",
            "pwd", "echo", "printf", "wc", "sort", "uniq", "cut", "tr", "diff", "cmp", "stat", "file", "du",
            "df", "which", "whereis", "type", "whoami", "id", "hostname", "uname", "date", "env", "printenv",
            "ps", "free", "uptime", "tree", "jq", "yq", "awk", "sed", "base64", "md5sum", "sha1sum",
            "sha256sum", "nslookup", "dig", "host", "ping", "traceroute", "netstat", "ss", "lsof", "test",
            "true", "false", "basename", "dirname", "realpath", "readlink", "column", "nl", "xxd", "od",
            "strings", "zcat", "cd", "sleep", "openssl", "getent", "nproc", "lscpu", "ip", "ifconfig",
            "curl", "git", "kubectl", "helm", "terraform", "aws", "az", "gcloud", "docker", "npm", "unzip",
            "tar", "mvn", "gradle", "java", "node", "python", "python3", "gh", "systemctl", "journalctl",
            // SF-176-07 (D4) : préfixes de shell sans effet hors du processus, lecteurs compressés.
            "export", "unset", "set", "zgrep", "zegrep", "zless", "bzcat", "xzcat");

    /** Programmes traités par une règle dédiée (les autres inconnus sont des modifications par prudence). */
    private static final Set<String> RULED_PROGRAMS = Set.of("rm", "rmdir", "shred", "dd", "mkfs", "ssh", "scp",
            "rsync", "sftp", "sendmail", "mail", "mutt", "reboot", "shutdown", "kill", "pkill", "killall", "oc",
            "tofu", "terragrunt", "podman", "glab");

    /** Redirection vers un fichier (hors /dev/null et duplication de descripteur). */
    private static final Pattern REDIRECT = Pattern.compile("(?<![0-9&])>>?\\s*(?!&|/dev/null)\\S"
            + "|[0-9]>>?\\s*(?!&|/dev/null)[^&\\s]");

    /**
     * La classe de risque de l'appel, ou {@code null} s'il n'est pas soumis à la porte.
     *
     * @param tool  nom de l'outil
     * @param input paramètres de l'appel
     */
    public static JourneyPlan.Risk classify(String tool, JsonNode input) {
        if (tool == null) {
            return null;
        }
        if (READ_TOOLS.contains(tool)) {
            return JourneyPlan.Risk.LECTURE;
        }
        if (FILE_WRITES.contains(tool)) {
            return isNotesPath(text(input, "path")) ? JourneyPlan.Risk.NOTES : JourneyPlan.Risk.REVERSIBLE;
        }
        if ("bash".equals(tool)) {
            return classifyCommand(text(input, "command"));
        }
        if ("task".equals(tool)) {
            // Une sous-tâche en lecture seule est une lecture ; une sous-tâche écrivaine édite (en branche).
            boolean readOnly = input != null && input.path("read_only").asBoolean(false);
            return readOnly ? JourneyPlan.Risk.LECTURE : JourneyPlan.Risk.REVERSIBLE;
        }
        if (fr.claudegateway.teams.TeamsToolCatalog.isWrite(tool)) {
            return JourneyPlan.Risk.EXTERNE;
        }
        if (fr.claudegateway.decks.DeckToolCatalog.isDeckTool(tool)
                || fr.claudegateway.office.OfficeToolCatalog.isOfficeTool(tool)
                || fr.claudegateway.diagrams.DiagramToolCatalog.isDiagramTool(tool)
                || fr.claudegateway.images.ImageToolCatalog.isImageTool(tool)) {
            // Ces outils DÉPOSENT un fichier dans le projet : une modification (réversible).
            return JourneyPlan.Risk.REVERSIBLE;
        }
        return null;
    }

    /** Vrai si le chemin désigne une note du sujet (STATE.md, PLAN-ACTION.md, notes/, carte/…). */
    public static boolean isNotesPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.strip().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains("..")) {
            return false;
        }
        String[] segments = normalized.split("/");
        String name = segments[segments.length - 1];
        if (NOTE_FILES.contains(name)) {
            return true;
        }
        for (int i = 0; i < segments.length - 1; i++) {
            if (NOTE_DIRS.contains(segments[i])) {
                return name.endsWith(".md");
            }
        }
        return false;
    }

    /**
     * La classe d'une commande shell : {@code LECTURE} si <b>chaque</b> segment est une lecture
     * reconnue ; {@code EXTERNE} si un segment touche un système externe ou détruit ; {@code REVERSIBLE}
     * sinon (prudence). L'<b>authentification du poste</b> ({@code aws sso login}, {@code az login}…)
     * n'est jamais une modification : {@code NOTES} si rien d'autre ne modifie (SF-176-07, D3).
     */
    public static JourneyPlan.Risk classifyCommand(String command) {
        if (command == null || command.isBlank()) {
            return JourneyPlan.Risk.LECTURE;
        }
        String unquoted = unquoted(command);
        boolean external = PIPE_TO_SHELL.matcher(unquoted).find();
        boolean modifies = REDIRECT.matcher(unquoted).find() || command.contains("$(") || command.contains("`");
        boolean auth = false;
        for (String segment : segments(command)) {
            List<String> words = words(segment);
            if (words.isEmpty()) {
                continue;
            }
            Verdict verdict = segment(words);
            if (verdict == Verdict.EXTERNAL) {
                external = true;
            } else if (verdict == Verdict.MODIFY) {
                modifies = true;
            } else if (verdict == Verdict.AUTH) {
                auth = true;
            }
        }
        if (external) {
            return JourneyPlan.Risk.EXTERNE;
        }
        if (modifies) {
            return JourneyPlan.Risk.REVERSIBLE;
        }
        return auth ? JourneyPlan.Risk.NOTES : JourneyPlan.Risk.LECTURE;
    }

    /**
     * Vrai si un segment de la commande lance un programme que la classification ne connaît pas — la
     * prudence en a fait une modification (mesure des refus « inconnu », SF-176-07 D4).
     */
    public static boolean hasUnknownProgram(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        for (String segment : segments(command)) {
            String program = program(stripPrefixes(words(segment)));
            if (program != null && !READ_COMMANDS.contains(program) && !RULED_PROGRAMS.contains(program)) {
                return true;
            }
        }
        return false;
    }

    /** Le nom du programme (sans chemin), ou {@code null}. */
    private static String program(List<String> w) {
        if (w.isEmpty()) {
            return null;
        }
        String program = w.get(0);
        int slash = program.lastIndexOf('/');
        return slash >= 0 ? program.substring(slash + 1) : program;
    }

    /** Un flux redirigé vers un interpréteur : n'importe quoi peut s'exécuter. */
    private static final Pattern PIPE_TO_SHELL = Pattern.compile("\\|\\s*(sudo\\s+)?(sh|bash|zsh|ksh)\\b");

    /** Le texte hors guillemets (le contenu des guillemets est remplacé par un « x »). */
    static String unquoted(String command) {
        StringBuilder out = new StringBuilder();
        char quote = 0;
        for (char c : command.toCharArray()) {
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                    out.append('x');
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Les commandes successives : découpe sur {@code && || ; |} et les sauts de ligne hors guillemets. */
    static List<String> segments(String command) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        char[] chars = command.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                current.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
            } else if (c == ';' || c == '\n' || c == '|' || c == '&' && i + 1 < chars.length && chars[i + 1] == '&') {
                out.add(current.toString());
                current.setLength(0);
                if ((c == '|' || c == '&') && i + 1 < chars.length && chars[i + 1] == c) {
                    i++;
                }
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }

    private enum Verdict { READ, MODIFY, EXTERNAL, AUTH }

    /** Retire les préfixes neutres : affectations d'environnement, sudo, timeout N, nice, command, exec. */
    private static List<String> stripPrefixes(List<String> raw) {
        List<String> w = new ArrayList<>(raw);
        // Préfixes neutres : affectations d'environnement, sudo, timeout N, nice, command, exec.
        while (!w.isEmpty()) {
            String first = w.get(0);
            if (first.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
                w.remove(0);
            } else if (first.equals("sudo") || first.equals("nice") || first.equals("command")
                    || first.equals("exec") || first.equals("nohup")) {
                w.remove(0);
            } else if (first.equals("timeout") && w.size() > 1) {
                w.remove(0);
                w.remove(0);
            } else {
                break;
            }
        }
        return w;
    }

    /**
     * L'authentification du poste (SF-176-07, D3) : se connecter, changer de compte, de projet ou de
     * contexte — jamais une modification du système ciblé.
     */
    static boolean isWorkstationAuth(String program, List<String> args) {
        List<String> plain = args.stream().filter(a -> !a.startsWith("-")).toList();
        String a0 = plain.isEmpty() ? "" : plain.get(0);
        String a1 = plain.size() > 1 ? plain.get(1) : "";
        String a2 = plain.size() > 2 ? plain.get(2) : "";
        return switch (program) {
            case "aws" -> a0.equals("sso") && (a1.equals("login") || a1.equals("logout"))
                    || a0.equals("configure") && a1.equals("sso");
            case "az" -> a0.equals("login") || a0.equals("logout") || a0.equals("account") && a1.equals("set");
            case "gcloud" -> a0.equals("auth") && (a1.equals("login") || a1.equals("revoke")
                    || a1.equals("application-default") && a2.equals("login"))
                    || a0.equals("config") && a1.equals("set");
            case "gh", "glab" -> a0.equals("auth")
                    && Set.of("login", "logout", "refresh", "switch", "setup-git").contains(a1);
            case "kubectl", "oc" -> a0.equals("config") && (a1.equals("use-context")
                    || a1.equals("set-context") && args.contains("--current"))
                    || program.equals("oc") && (a0.equals("login") || a0.equals("project"));
            default -> false;
        };
    }

    private static Verdict segment(List<String> raw) {
        List<String> w = stripPrefixes(raw);
        if (w.isEmpty()) {
            return Verdict.READ;
        }
        String program = program(w);
        List<String> args = w.subList(1, w.size());
        if (isWorkstationAuth(program, args)) {
            return Verdict.AUTH;
        }
        return switch (program) {
            case "rm", "rmdir", "shred", "dd", "mkfs", "ssh", "scp", "rsync", "sftp", "sendmail", "mail",
                 "mutt", "reboot", "shutdown", "kill", "pkill", "killall" -> Verdict.EXTERNAL;
            case "find" -> has(args, "-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint")
                    ? Verdict.MODIFY : Verdict.READ;
            case "sed" -> args.stream().anyMatch(a -> a.equals("-i") || a.startsWith("-i") && !a.startsWith("-in")
                    || a.startsWith("--in-place")) ? Verdict.MODIFY : Verdict.READ;
            case "awk" -> String.join(" ", args).contains("system(") ? Verdict.MODIFY : Verdict.READ;
            case "tar" -> !args.isEmpty() && args.get(0).replace("-", "").startsWith("t") ? Verdict.READ
                    : Verdict.MODIFY;
            case "unzip" -> has(args, "-l", "-v", "-t") ? Verdict.READ : Verdict.MODIFY;
            case "git" -> git(args);
            case "kubectl", "oc" -> kubectl(args);
            case "helm" -> firstOf(args, Set.of("list", "ls", "status", "get", "history", "template", "show",
                    "search", "version", "lint", "diff", "env", "repo")) ? Verdict.READ
                    : firstOf(args, Set.of("install", "upgrade", "uninstall", "delete", "rollback"))
                            ? Verdict.EXTERNAL : Verdict.MODIFY;
            case "terraform", "tofu", "terragrunt" -> terraform(args);
            case "aws" -> aws(args);
            case "az", "gcloud" -> args.stream().anyMatch(a -> Set.of("list", "show", "describe", "get",
                    "get-credentials", "version", "account").contains(a))
                    && args.stream().noneMatch(a -> Set.of("create", "delete", "update", "set", "deploy",
                    "start", "stop", "restart").contains(a)) ? Verdict.READ : Verdict.EXTERNAL;
            case "docker", "podman" -> firstOf(args, Set.of("ps", "images", "logs", "inspect", "version", "info",
                    "stats", "top")) ? Verdict.READ
                    : firstOf(args, Set.of("push", "login")) ? Verdict.EXTERNAL : Verdict.MODIFY;
            case "curl" -> curl(args);
            case "gh" -> firstOf(args, Set.of("auth", "api")) && !has(args, "-X", "--method", "-f", "--field")
                    || args.size() > 1 && Set.of("list", "view", "status", "diff", "checks").contains(args.get(1))
                    ? Verdict.READ : Verdict.EXTERNAL;
            case "npm" -> firstOf(args, Set.of("ls", "list", "view", "outdated", "-v", "--version", "config"))
                    ? Verdict.READ : firstOf(args, Set.of("publish")) ? Verdict.EXTERNAL : Verdict.MODIFY;
            case "systemctl" -> firstOf(args, Set.of("status", "list-units", "is-active", "show", "cat"))
                    ? Verdict.READ : Verdict.EXTERNAL;
            case "openssl" -> firstOf(args, Set.of("x509", "s_client", "version", "verify", "req"))
                    && !has(args, "-out", "-new") ? Verdict.READ : Verdict.MODIFY;
            case "java", "node", "python", "python3" -> has(args, "-version", "--version", "-V") ? Verdict.READ
                    : Verdict.MODIFY;
            case "mvn", "gradle" -> Verdict.MODIFY;
            default -> READ_COMMANDS.contains(program) ? Verdict.READ : Verdict.MODIFY;
        };
    }

    private static Verdict git(List<String> args) {
        List<String> a = stripGitOptions(args);
        if (a.isEmpty()) {
            return Verdict.READ;
        }
        String sub = a.get(0);
        List<String> rest = a.subList(1, a.size());
        return switch (sub) {
            case "status", "log", "diff", "show", "rev-parse", "describe", "blame", "ls-files", "ls-tree",
                 "cat-file", "shortlog", "reflog", "grep", "fetch", "ls-remote", "whatchanged", "version",
                 "help", "rev-list", "merge-base", "name-rev", "count-objects", "fsck", "check-ignore" ->
                    Verdict.READ;
            case "branch" -> has(rest, "-d", "-D", "-m", "-M", "-c", "-C", "--delete", "--move", "-u",
                    "--set-upstream-to") || rest.stream().anyMatch(r -> !r.startsWith("-"))
                    && !has(rest, "--list", "-l", "--contains", "--merged", "--no-merged", "--points-at")
                    ? Verdict.MODIFY : Verdict.READ;
            case "remote" -> rest.isEmpty() || has(rest, "-v", "show", "get-url") ? Verdict.READ : Verdict.MODIFY;
            case "tag" -> rest.isEmpty() || has(rest, "-l", "--list") ? Verdict.READ : Verdict.MODIFY;
            case "stash" -> !rest.isEmpty() && (rest.get(0).equals("list") || rest.get(0).equals("show"))
                    ? Verdict.READ : Verdict.MODIFY;
            case "config" -> has(rest, "--get", "--list", "-l", "--get-all", "--show-origin") ? Verdict.READ
                    : Verdict.MODIFY;
            case "push", "merge", "rebase", "reset", "clean", "filter-branch", "filter-repo", "gc", "prune" ->
                    sub.equals("reset") && !has(rest, "--hard") ? Verdict.MODIFY : Verdict.EXTERNAL;
            default -> Verdict.MODIFY;
        };
    }

    private static List<String> stripGitOptions(List<String> args) {
        List<String> out = new ArrayList<>(args);
        while (!out.isEmpty() && out.get(0).startsWith("-")) {
            String opt = out.remove(0);
            if ((opt.equals("-C") || opt.equals("-c") || opt.equals("--git-dir") || opt.equals("--work-tree"))
                    && !out.isEmpty()) {
                out.remove(0);
            }
        }
        return out;
    }

    private static Verdict kubectl(List<String> args) {
        String sub = firstNonOption(args, Set.of("-n", "--namespace", "--context", "--kubeconfig", "-l",
                "--selector", "-o", "--output", "-c", "--container"));
        if (sub == null) {
            return Verdict.READ;
        }
        if (Set.of("get", "describe", "logs", "top", "explain", "version", "api-resources", "api-versions",
                "cluster-info", "diff", "events", "wait").contains(sub)) {
            return Verdict.READ;
        }
        if (sub.equals("config")) {
            return has(args, "view", "get-contexts", "current-context", "get-clusters") ? Verdict.READ
                    : Verdict.MODIFY;
        }
        if (sub.equals("auth")) {
            return has(args, "can-i", "whoami") ? Verdict.READ : Verdict.EXTERNAL;
        }
        if (sub.equals("rollout")) {
            return has(args, "status", "history") ? Verdict.READ : Verdict.EXTERNAL;
        }
        return Verdict.EXTERNAL; // apply, delete, create, patch, scale, edit, exec, port-forward, drain…
    }

    private static Verdict terraform(List<String> args) {
        String sub = firstNonOption(args, Set.of("-chdir"));
        if (sub == null) {
            return Verdict.READ;
        }
        if (Set.of("plan", "show", "validate", "output", "version", "providers", "graph", "fmt", "init",
                "console", "workspace").contains(sub)) {
            if (sub.equals("fmt") && !has(args, "-check")) {
                return Verdict.MODIFY;
            }
            return sub.equals("workspace") && !has(args, "list", "show") ? Verdict.MODIFY : Verdict.READ;
        }
        if (sub.equals("state")) {
            return has(args, "list", "show", "pull") ? Verdict.READ : Verdict.EXTERNAL;
        }
        return Verdict.EXTERNAL; // apply, destroy, import, taint, refresh…
    }

    private static Verdict aws(List<String> args) {
        List<String> plain = args.stream().filter(a -> !a.startsWith("-")).toList();
        if (plain.size() < 2) {
            return plain.isEmpty() || plain.get(0).equals("configure") && has(args, "list", "get")
                    ? Verdict.READ : Verdict.MODIFY;
        }
        String service = plain.get(0);
        String op = plain.get(1);
        if (service.equals("s3")) {
            return op.equals("ls") ? Verdict.READ : Verdict.EXTERNAL;
        }
        if (service.equals("sts") && op.startsWith("get-")) {
            return Verdict.READ;
        }
        if (service.equals("logs") && (op.equals("tail") || op.startsWith("filter-") || op.startsWith("get-")
                || op.startsWith("describe-"))) {
            return Verdict.READ;
        }
        if (op.startsWith("describe") || op.startsWith("list") || op.startsWith("get-") || op.equals("wait")
                || op.startsWith("lookup") || op.startsWith("search") || op.startsWith("batch-get")) {
            return Verdict.READ;
        }
        return Verdict.EXTERNAL;
    }

    private static Verdict curl(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-X") || a.equals("--request")) {
                String method = i + 1 < args.size() ? args.get(i + 1).toUpperCase(Locale.ROOT) : "";
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    return Verdict.EXTERNAL;
                }
            } else if (a.startsWith("-X") && a.length() > 2) {
                String method = a.substring(2).toUpperCase(Locale.ROOT);
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    return Verdict.EXTERNAL;
                }
            } else if (a.equals("-d") || a.startsWith("--data") || a.equals("-F") || a.equals("--form")
                    || a.equals("-T") || a.equals("--upload-file") || a.equals("--json")) {
                return Verdict.EXTERNAL;
            } else if (a.equals("-o") || a.equals("-O") || a.equals("--output") || a.equals("--remote-name")) {
                return Verdict.MODIFY;
            }
        }
        return Verdict.READ;
    }

    private static String firstNonOption(List<String> args, Set<String> optionsWithValue) {
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.startsWith("-")) {
                if (optionsWithValue.contains(a) && !a.contains("=")) {
                    i++;
                }
                continue;
            }
            return a;
        }
        return null;
    }

    private static boolean firstOf(List<String> args, Set<String> values) {
        String first = firstNonOption(args, Set.of());
        return first != null && values.contains(first) || !args.isEmpty() && values.contains(args.get(0));
    }

    private static boolean has(List<String> args, String... values) {
        for (String a : args) {
            for (String v : values) {
                if (a.equals(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Découpe un segment en mots (guillemets simples et doubles respectés, sans interprétation). */
    static List<String> words(String segment) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inWord = false;
        for (char c : segment.strip().toCharArray()) {
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    out.add(current.toString());
                    current.setLength(0);
                    inWord = false;
                }
            } else {
                current.append(c);
                inWord = true;
            }
        }
        if (inWord) {
            out.add(current.toString());
        }
        return out;
    }

    private static String text(JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field)) {
            return null;
        }
        return input.get(field).asText(null);
    }
}
