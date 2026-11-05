package fr.claudegateway.jarvis.state.extract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>La forme réelle d'une commande</b> (F-247 / SF-247-17) : l'usage du PO n'est pas « {@code kubectl get deploy} »
 * mais « {@code cd ~/dev && K=cloudops-run/kubectl-devtools.sh; timeout 60 $K get deploy -o json} ». Deux lectures,
 * pures et sans exception :
 * <ul>
 * <li>{@link #resolve} : ramène une commande composée à <b>son unique instruction utile</b> (les préfixes muets
 * {@code cd}, {@code export}, affectations, {@code set -e} sont retirés), en résolvant l'alias de binaire en tête
 * ({@code A=~/aws-cli/aws; $A ecs …} ⇒ {@code aws ecs …}). S'il reste plus d'une instruction (sorties mêlées), la
 * commande est rendue telle quelle : en cas de doute, les extracteurs ne lisent rien ;</li>
 * <li>{@link #fileOnly} : vrai si toutes les instructions sont des outils de fichiers ou de texte ({@code cat},
 * {@code sed}, {@code grep}, {@code ls}…) — leur sortie est du contenu de fichier, pas un état.</li>
 * </ul>
 */
public final class CommandShape {

    /** Outils de fichiers et de texte : leur sortie est du contenu, déjà couvert par la lecture de fichiers. */
    static final Set<String> FILE_TOOLS = Set.of("cat", "sed", "grep", "egrep", "fgrep", "rg", "ls", "head", "tail",
            "wc", "find", "awk", "cut", "sort", "uniq", "diff", "echo", "printf", "pwd", "mkdir", "cp", "mv", "rm",
            "touch", "chmod", "chown", "ln", "date", "sleep", "which", "type", "file", "stat", "tree", "du", "df",
            "less", "more", "tee", "xargs", "tr", "nl", "column", "basename", "dirname", "realpath", "readlink",
            "base64", "md5sum", "md5", "shasum", "sha256sum", "unzip", "zip", "tar", "gzip", "gunzip", "iconv",
            "paste", "comm", "join", "fold", "rev", "seq", "cmp", "open", "clear", "history", "whoami", "hostname",
            "uname", "id", "env", "printenv");
    /** Mots du shell sans sortie propre, ou préfixes à sauter pour trouver l'outil. */
    private static final Set<String> SHELL_WORDS = Set.of("export", "set", "cd", "unset", "local", "shift", "wait",
            "return", "exit", "true", "false", ":", "for", "case", "esac", "done", "fi", "}", ")", "[", "[[", "test",
            "trap", "pushd", "popd", "read", "break", "continue", "declare", "typeset", "alias", "source", ".");
    private static final Set<String> PREFIX_WORDS = Set.of("do", "then", "else", "elif", "if", "while", "until",
            "{", "(", "!", "time", "sudo", "nohup", "command", "exec");
    /** Les binaires dont un alias est résolu. */
    private static final Set<String> TOOLS = Set.of("aws", "kubectl", "oc", "helm", "glab", "gh", "az", "terraform",
            "argocd");
    private static final Pattern ASSIGN = Pattern.compile(
            "^([A-Za-z_]\\w*)=(\\$\\((?:[^()]|\\([^()]*\\))*\\)|\"[^\"]*\"|'[^']*'|\\S*)\\s*");
    private static final Pattern TIMEOUT = Pattern.compile("^timeout\\s+(?:-\\S+\\s+)*\\d+[smh]?\\s+");
    private static final Pattern HEREDOC = Pattern.compile("<<-?\\s*['\"]?([A-Za-z_]\\w*)['\"]?");
    private static final Pattern ALIAS_HEAD = Pattern.compile("^\\$\\{?([A-Za-z_]\\w*)\\}?(?=\\s|$)");

    private CommandShape() {
    }

    /**
     * L'unique instruction utile d'une commande composée, alias de binaire résolu ; la commande telle quelle s'il y en
     * a plusieurs (ou aucune).
     */
    public static String resolve(String command) {
        if (command == null || command.isBlank()) {
            return command;
        }
        try {
            String text = withoutHeredocBodies(command);
            Map<String, String> aliases = new HashMap<>();
            Map<String, String> vars = new HashMap<>();
            List<String> useful = new ArrayList<>();
            for (String st : split(text, false)) {
                String s = st.strip();
                if (s.isEmpty()) {
                    continue;
                }
                String rest = stripAssignments(s, aliases, vars);
                if (rest.isEmpty() || silent(rest)) {
                    continue;
                }
                useful.add(rest);
            }
            if (useful.size() != 1 || text.length() != command.length()) {
                // Plusieurs instructions (sorties mêlées) ou un texte en ligne (heredoc) : on ne touche à rien.
                return command;
            }
            String main = useful.get(0);
            Matcher t = TIMEOUT.matcher(main);
            if (t.find()) {
                main = main.substring(t.end());
            }
            Matcher a = ALIAS_HEAD.matcher(main);
            if (a.find() && aliases.containsKey(a.group(1))) {
                main = aliases.get(a.group(1)) + main.substring(a.end());
            }
            // Les variables littérales posées avant ({@code H=forge.ex; A="https://$H/api/v4/…"; curl "$A"}).
            return substitute(main, vars).strip();
        } catch (RuntimeException e) {
            return command;
        }
    }

    /** Vrai si toutes les instructions de la commande sont des outils de fichiers ou de texte (ou des mots du shell). */
    public static boolean fileOnly(String command) {
        if (command == null || command.isBlank()) {
            return true;
        }
        try {
            List<String> heads = heads(command);
            if (heads.isEmpty()) {
                return true;
            }
            for (String h : heads) {
                if (!FILE_TOOLS.contains(h) && !SHELL_WORDS.contains(h)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Les outils en tête de chaque instruction (pipes compris), alias résolus, en minuscules, sans chemin. */
    static List<String> heads(String command) {
        String text = withoutHeredocBodies(command);
        Map<String, String> aliases = new HashMap<>();
        List<String> out = new ArrayList<>();
        for (String st : split(text, true)) {
            String s = stripAssignments(st.strip(), aliases);
            String head = null;
            for (int guard = 0; guard < 6 && !s.isEmpty(); guard++) {
                Matcher t = TIMEOUT.matcher(s);
                if (t.find()) {
                    s = s.substring(t.end());
                    continue;
                }
                String w = s.split("\\s+", 2)[0];
                if (PREFIX_WORDS.contains(w)) {
                    s = s.length() > w.length() ? s.substring(w.length()).strip() : "";
                    s = stripAssignments(s, aliases);
                    continue;
                }
                head = w;
                break;
            }
            if (head == null || head.isEmpty()) {
                continue;
            }
            Matcher a = ALIAS_HEAD.matcher(head);
            if (a.find()) {
                head = aliases.getOrDefault(a.group(1), head);
            }
            head = head.replaceAll("^[\"']|[\"']$", "");
            int slash = head.lastIndexOf('/');
            head = (slash >= 0 ? head.substring(slash + 1) : head).toLowerCase(Locale.ROOT);
            if (!head.isEmpty()) {
                out.add(head);
            }
        }
        return out;
    }

    /** Retire les affectations en tête ; note celles qui désignent un binaire connu (alias). */
    private static String stripAssignments(String s, Map<String, String> aliases) {
        return stripAssignments(s, aliases, null);
    }

    /** Idem, en notant aussi les valeurs littérales ({@code H=forge.ex}) pour les substituer dans l'instruction utile. */
    private static String stripAssignments(String s, Map<String, String> aliases, Map<String, String> vars) {
        String rest = s;
        for (int guard = 0; guard < 20; guard++) {
            Matcher m = ASSIGN.matcher(rest);
            if (!m.find() || m.end() == 0) {
                break;
            }
            String tool = toolOf(m.group(2));
            if (tool != null) {
                aliases.put(m.group(1), tool);
            }
            if (vars != null) {
                String raw = m.group(2);
                String v = raw.length() >= 2 && (raw.startsWith("\"") && raw.endsWith("\"")
                        || raw.startsWith("'") && raw.endsWith("'")) ? raw.substring(1, raw.length() - 1) : raw;
                if (v.contains("$(") || v.contains("`")) {
                    vars.remove(m.group(1)); // valeur calculée : jamais substituée
                } else {
                    vars.put(m.group(1), substitute(v, vars));
                }
            }
            rest = rest.substring(m.end());
        }
        return rest.strip();
    }

    /** Remplace {@code $NOM} / {@code ${NOM}} par la valeur littérale notée (les noms les plus longs d'abord). */
    static String substitute(String text, Map<String, String> vars) {
        String out = text;
        List<String> names = new ArrayList<>(vars.keySet());
        names.sort((a, b) -> b.length() - a.length());
        for (String n : names) {
            String v = Matcher.quoteReplacement(vars.get(n));
            out = out.replaceAll("\\$\\{" + n + "\\}", v).replaceAll("\\$" + n + "(?![\\w])", v);
        }
        return out;
    }

    /** Le binaire désigné par une valeur d'affectation ({@code ~/aws-cli/aws}, {@code x/kubectl-devtools.sh}). */
    static String toolOf(String value) {
        String v = value.replaceAll("^[\"']|[\"']$", "").strip();
        if (v.isEmpty() || v.contains(" ") || v.contains("$(")) {
            return null;
        }
        int slash = v.lastIndexOf('/');
        String base = (slash >= 0 ? v.substring(slash + 1) : v).toLowerCase(Locale.ROOT);
        base = base.replaceAll("\\.(sh|exe|cmd)$", "");
        if (TOOLS.contains(base)) {
            return base;
        }
        if (base.startsWith("kubectl")) {
            return "kubectl";
        }
        return null;
    }

    /** Une instruction sans sortie propre : {@code cd}, {@code export}, {@code set -e}, {@code unset}. */
    private static boolean silent(String s) {
        String w = s.split("\\s+", 2)[0];
        return w.equals("cd") || w.equals("export") || w.equals("unset")
                || (w.equals("set") && s.matches("set(\\s+[-+]\\w+)+\\s*"));
    }

    /** Retire le corps des heredocs (leurs lignes ne sont pas des commandes). */
    static String withoutHeredocBodies(String command) {
        String[] lines = command.split("\n", -1);
        StringBuilder b = new StringBuilder();
        String until = null;
        for (String line : lines) {
            if (until != null) {
                if (line.strip().equals(until)) {
                    until = null;
                }
                continue;
            }
            Matcher m = HEREDOC.matcher(line);
            if (m.find()) {
                until = m.group(1);
            }
            if (b.length() > 0) {
                b.append('\n');
            }
            b.append(line);
        }
        return b.toString();
    }

    /** Découpe aux séparateurs hors guillemets : {@code ; && || \n} (et {@code |} si demandé). */
    static List<String> split(String text, boolean pipes) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < text.length()) {
                    cur.append(text.charAt(++i));
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
                continue;
            }
            if (c == '\\' && i + 1 < text.length()) {
                char n = text.charAt(i + 1);
                if (n == '\n') {
                    i++;
                    cur.append(' ');
                    continue;
                }
                cur.append(c).append(n);
                i++;
                continue;
            }
            if (!pipes && c == '(' && i > 0 && text.charAt(i - 1) == '$') {
                depth++;
                cur.append(c);
                continue;
            }
            if (!pipes && depth > 0) {
                // dans une substitution $( … ) : c'est une seule instruction (affectation capturée)
                if (c == ')') {
                    depth--;
                } else if (c == '(') {
                    depth++;
                }
                cur.append(c);
                continue;
            }
            boolean sep = c == ';' || c == '\n';
            if (c == '&' && i + 1 < text.length() && text.charAt(i + 1) == '&') {
                sep = true;
                i++;
            } else if (c == '|') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '|') {
                    sep = true;
                    i++;
                } else if (pipes) {
                    sep = true;
                }
            } else if (pipes && (c == '(' && i > 0 && text.charAt(i - 1) == '$')) {
                // $( … ) : la sous-commande est une instruction à part
                cur.setLength(Math.max(0, cur.length() - 1));
                sep = true;
            }
            if (sep) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
