package fr.claudegateway.atelier.veille;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>La sécurité de la sonde</b> (F-230 / SF-230-01, D5) : une commande qui tourne sans humain doit être en
 * <b>lecture seule</b> et ne porter <b>aucun secret en clair</b>. Calcul pur.
 *
 * <p>Ce contrôle est une défense en profondeur, pas une preuve : la vraie garde est que le PO <b>voit</b> la
 * commande sur la carte, la <b>teste</b> et l'<b>active</b> lui-même, et que le script validé est épinglé par son
 * empreinte. Les formes refusées ici sont celles qui ne devraient <b>jamais</b> apparaître dans une sonde :</p>
 * <ul>
 *   <li>un littéral qui ressemble à un jeton (formes manifestes de {@code ClientMailSecrets}, jeton Atlassian
 *       {@code ATATT…}, en-tête d'autorisation écrit en dur) — le jeton se lit dans un fichier ou une variable
 *       du poste ;</li>
 *   <li>une méthode d'écriture HTTP ({@code PUT}, {@code PATCH}, {@code DELETE}, et {@code POST} hors GraphQL) ;</li>
 *   <li>une {@code mutation} GraphQL.</li>
 * </ul>
 * <p>Les messages nomment la <b>nature</b> du problème, jamais la valeur trouvée.</p>
 */
public final class JarvisWatchProbeGuard {

    /** Un script appelé par la commande : il doit être déclaré, relu et épinglé. */
    static final Pattern SCRIPT_REFERENCE = Pattern.compile(
            "(?i)[\\w./~\\\\-]+\\.(?:sh|bash|py|js|mjs|cjs|ts|ps1|rb)\\b");

    private record Rule(String label, Pattern pattern) {
    }

    /**
     * Les formes <b>manifestes</b> d'un secret écrit en dur. Volontairement pas la règle « mot-clé = valeur » de
     * {@code ClientMailSecrets} : dans du code, {@code token = os.environ["JIRA_TOKEN"]} est la <b>bonne</b>
     * pratique et ne doit pas être refusé. Seul un <b>littéral entre guillemets</b> long et mêlé de chiffres,
     * affecté à un nom de secret, l'est.
     */
    private static final java.util.List<Rule> SECRETS = java.util.List.of(
            new Rule("une clé privée", Pattern.compile("-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----")),
            new Rule("une clé d'accès AWS", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new Rule("un jeton GitHub", Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})")),
            new Rule("un jeton GitLab", Pattern.compile("\\bglpat-[A-Za-z0-9._-]{20,}")),
            new Rule("un jeton Slack", Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}")),
            new Rule("un jeton Atlassian", Pattern.compile("\\bATATT[A-Za-z0-9_=-]{20,}")),
            new Rule("une clé d'API", Pattern.compile("\\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{20,}")),
            new Rule("une clé d'API Google", Pattern.compile("\\bAIza[0-9A-Za-z_-]{30,}")),
            new Rule("un jeton JWT", Pattern.compile(
                    "\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")),
            new Rule("un jeton d'authentification", Pattern.compile(
                    "(?i)\\bauthorization\\s*:\\s*(?:bearer|basic|token)\\s+[A-Za-z0-9._~+/=-]{16,}")),
            new Rule("un jeton d'authentification", Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{20,}")),
            new Rule("un identifiant et son mot de passe", Pattern.compile(
                    "\\s(?:-u|--user)\\s+['\"]?[^\\s:'\"$]+:(?!\\$)[^\\s'\"]{8,}")),
            new Rule("un secret", Pattern.compile(
                    "(?i)(?:token|password|passwd|secret|api[_-]?key|apikey)\\w*['\"]?\\s*[:=]\\s*['\"]"
                            + "(?=[A-Za-z0-9+/=._~-]*[0-9])(?=[A-Za-z0-9+/=._~-]*[A-Za-z])[A-Za-z0-9+/=._~-]{16,}['\"]")));

    /** {@code curl -X PUT}, {@code --request DELETE}, {@code -XPOST}… */
    private static final Pattern CURL_METHOD = Pattern.compile(
            "(?i)(?:-X\\s*|--request[\\s=]+)['\"]?(POST|PUT|PATCH|DELETE)\\b");

    /** {@code requests.post(…)}, {@code httpx.delete(…)}, {@code axios.put(…)}, {@code session.patch(…)}. */
    private static final Pattern CLIENT_METHOD = Pattern.compile(
            "(?i)\\b(?:requests|httpx|axios|session|client|http)\\s*\\.\\s*(post|put|patch|delete)\\s*\\(");

    /** {@code method: 'POST'}, {@code "method": "DELETE"}, {@code -Method Put} (PowerShell). */
    private static final Pattern METHOD_FIELD = Pattern.compile(
            "(?i)(?:\\bmethod['\"]?\\s*[:=]\\s*['\"]?|-Method\\s+['\"]?)(POST|PUT|PATCH|DELETE)\\b");

    /** {@code curl -d …} / {@code --data …} sans méthode : curl envoie un POST. */
    private static final Pattern CURL_DATA = Pattern.compile("(?i)\\bcurl\\b[^|;&\\n]*\\s(?:-d|--data(?:-raw|-binary)?|--json)\\b");

    /** Une mutation GraphQL. */
    private static final Pattern GRAPHQL_MUTATION = Pattern.compile("(?i)\\bmutation\\b\\s*[\\w{(]");

    /** La présence de GraphQL : seul cas où un {@code POST} est une lecture. */
    private static final Pattern GRAPHQL = Pattern.compile("(?i)graphql|\\bquery\\s*[{(:\"']");

    private JarvisWatchProbeGuard() {
    }

    /**
     * Le premier problème de sécurité de ce texte (commande ou script), ou vide.
     *
     * @param text  la commande ou le contenu du script ({@code null} toléré)
     * @param where « la commande » ou « le script », pour la phrase
     */
    public static Optional<String> problemIn(String text, String where) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (Rule rule : SECRETS) {
            if (rule.pattern().matcher(text).find()) {
                return Optional.of(capital(where) + " contient " + rule.label() + " en clair : la sonde doit lire "
                        + "son jeton dans un fichier ou une variable du poste, jamais l'écrire.");
            }
        }
        if (GRAPHQL_MUTATION.matcher(text).find()) {
            return Optional.of(capital(where) + " contient une mutation GraphQL : une veille ne fait que lire.");
        }
        boolean graphql = GRAPHQL.matcher(text).find();
        for (Pattern pattern : new Pattern[] {CURL_METHOD, CLIENT_METHOD, METHOD_FIELD}) {
            Matcher method = pattern.matcher(text);
            while (method.find()) {
                String verb = method.group(1).toUpperCase(Locale.ROOT);
                if (!"POST".equals(verb) || !graphql) {
                    return Optional.of(capital(where) + " envoie une requête " + verb
                            + " : une veille ne fait que lire (GET, ou requête GraphQL sans mutation).");
                }
            }
        }
        if (!graphql && CURL_DATA.matcher(text).find()) {
            return Optional.of(capital(where) + " envoie des données (curl -d) hors GraphQL : une veille ne fait "
                    + "que lire (GET, ou requête GraphQL sans mutation).");
        }
        return Optional.empty();
    }

    // ------------------------------------------------------- F-247 / SF-247-08 : relevé, verbes de lecture

    /** Les verbes de lecture admis pour une sonde de relevé (cadrage §4.3). */
    static final java.util.Set<String> READ_VERBS = java.util.Set.of("get", "list", "ls", "view", "describe",
            "status", "show", "log", "output", "history", "top", "version", "info", "search", "checks", "diff",
            "branch", "rev-parse", "logs", "whoami", "current-context", "get-contexts");

    /** Les outils dont le verbe se lit au premier mot non-option après le nom (et son sous-groupe éventuel). */
    private static final java.util.Map<String, Integer> VERB_DEPTH = java.util.Map.ofEntries(
            java.util.Map.entry("kubectl", 2), java.util.Map.entry("oc", 2), java.util.Map.entry("helm", 1),
            java.util.Map.entry("argocd", 2), java.util.Map.entry("glab", 2), java.util.Map.entry("gh", 2),
            java.util.Map.entry("terraform", 2), java.util.Map.entry("git", 1), java.util.Map.entry("jira", 2),
            java.util.Map.entry("acli", 2), java.util.Map.entry("docker", 1), java.util.Map.entry("az", -1),
            java.util.Map.entry("aws", 2), java.util.Map.entry("gcloud", -1));

    /** Les écritures SQL, interdites dans un relevé. */
    private static final Pattern SQL_WRITE = Pattern.compile(
            "(?i)\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|merge|call)\\b\\s");

    /**
     * Le problème d'une sonde de relevé qui utiliserait un autre verbe que la lecture : pour chaque appel d'un outil
     * connu (kubectl, helm, argocd, glab, gh, terraform, git, aws, az…), le verbe doit être de lecture ; {@code aws}
     * n'admet que {@code describe-*}, {@code get-*}, {@code list-*} ; {@code psql}/{@code sqlcmd} aucune écriture.
     */
    public static Optional<String> stateVerbProblem(String text, String where) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (String segment : text.split("[\\n;|&]+|\\$\\(|`")) {
            String[] words = segment.strip().split("\\s+");
            for (int i = 0; i < words.length; i++) {
                String tool = words[i].replaceAll("^.*/", "").toLowerCase(Locale.ROOT);
                Integer depth = VERB_DEPTH.get(tool);
                if (depth == null) {
                    if ((tool.equals("psql") || tool.equals("sqlcmd") || tool.equals("mysql"))
                            && SQL_WRITE.matcher(segment).find()) {
                        return Optional.of(capital(where) + " écrit en base : un relevé ne fait que lire.");
                    }
                    continue;
                }
                List<String> verbs = new java.util.ArrayList<>();
                for (int j = i + 1; j < words.length && verbs.size() < Math.max(1, Math.abs(depth)); j++) {
                    if (!words[j].startsWith("-")) {
                        verbs.add(words[j].toLowerCase(Locale.ROOT));
                    } else if (j + 1 < words.length && !words[j].contains("=") && takesValue(words[j])) {
                        j++;
                    }
                }
                if (verbs.isEmpty()) {
                    break;
                }
                if (!readVerb(tool, depth, verbs, words, i)) {
                    return Optional.of(capital(where) + " appelle « " + tool + " " + String.join(" ", verbs)
                            + " » : un relevé n'admet que les verbes de lecture (get, list, view, describe, status, "
                            + "show, log, output, history).");
                }
                break;
            }
        }
        return Optional.empty();
    }

    private static boolean readVerb(String tool, int depth, List<String> verbs, String[] words, int at) {
        String last = verbs.get(verbs.size() - 1);
        if ("aws".equals(tool)) {
            return verbs.size() < 2 || last.startsWith("describe-") || last.startsWith("get-")
                    || last.startsWith("list-") || "ls".equals(last);
        }
        if (depth < 0) {
            // az / gcloud : le verbe est le DERNIER mot non-option de l'appel
            String verb = null;
            for (int j = at + 1; j < words.length; j++) {
                if (!words[j].startsWith("-")) {
                    verb = words[j].toLowerCase(Locale.ROOT);
                } else {
                    break;
                }
            }
            return verb == null || READ_VERBS.contains(verb) || verb.startsWith("show") || verb.startsWith("list");
        }
        // glab mr view / gh pr list : le verbe est le 2ᵉ mot ; « gh api » n'est admis qu'en GET (garde générale).
        if (("gh".equals(tool) || "glab".equals(tool)) && "api".equals(verbs.get(0))) {
            return true;
        }
        if ("git".equals(tool)) {
            return READ_VERBS.contains(verbs.get(0)) || "describe".equals(verbs.get(0)) || "remote".equals(verbs.get(0))
                    || "for-each-ref".equals(verbs.get(0)) || "rev-list".equals(verbs.get(0));
        }
        String second = verbs.size() > 1 ? verbs.get(1) : "";
        if ("terraform".equals(tool)) {
            return "state".equals(verbs.get(0)) ? READ_VERBS.contains(second)
                    : READ_VERBS.contains(verbs.get(0));
        }
        if ("kubectl".equals(tool) || "oc".equals(tool)) {
            if ("rollout".equals(verbs.get(0)) || "config".equals(verbs.get(0))) {
                return READ_VERBS.contains(second); // rollout status|history, config view|current-context
            }
            return READ_VERBS.contains(verbs.get(0)) || "api-resources".equals(verbs.get(0));
        }
        return READ_VERBS.contains(last);
    }

    /** Les options courantes qui prennent une valeur séparée (-n prod, --context x, -o json…). */
    private static boolean takesValue(String option) {
        return java.util.Set.of("-n", "--namespace", "--context", "-o", "--output", "-R", "--repo", "-C",
                "--profile", "--region", "-p", "--project", "-g", "--resource-group", "--subscription", "-c", "-l",
                "--selector", "-A").contains(option) && !"-A".equals(option);
    }

    /** Vrai si la commande appelle un fichier de script (qui doit alors être déclaré). */
    public static boolean referencesScript(String command) {
        return command != null && SCRIPT_REFERENCE.matcher(command).find();
    }

    private static String capital(String where) {
        return where.isEmpty() ? where : Character.toUpperCase(where.charAt(0)) + where.substring(1);
    }
}
