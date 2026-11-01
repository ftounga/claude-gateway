package fr.claudegateway.atelier.veille;

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

    /** Vrai si la commande appelle un fichier de script (qui doit alors être déclaré). */
    public static boolean referencesScript(String command) {
        return command != null && SCRIPT_REFERENCE.matcher(command).find();
    }

    private static String capital(String where) {
        return where.isEmpty() ? where : Character.toUpperCase(where.charAt(0)) + where.substring(1);
    }
}
