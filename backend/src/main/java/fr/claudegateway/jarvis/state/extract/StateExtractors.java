package fr.claudegateway.jarvis.state.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * <b>Le jeu d'extracteurs</b> (F-247 / SF-247-04, cadrage §4.2) et la <b>classification d'une commande d'état</b> pour
 * le taux de couverture : une commande d'état est une lecture d'un outil des familles visées (tickets, revues, CI,
 * déploiements, cloud, infra, base, git, API) ; sa <b>forme</b> ({@code curl /rest/api/2/issue}, {@code glab mr view},
 * {@code kubectl get}…) est ce que la mesure liste quand aucun extracteur ne l'a couverte.
 */
@Component
public class StateExtractors {

    /** Le premier mot exécutable d'une commande, après {@code sudo}, variables et chemins. */
    private static final Pattern HEAD = Pattern.compile("^(?:sudo\\s+)?(?:\\w+=\\S+\\s+)*(?:\\S*/)?([\\w.\\-]+)(?:\\s+(\\S+))?(?:\\s+(\\S+))?");
    private static final Pattern API_PATH = Pattern.compile("(/rest/api/[\\w]+/\\w+|/api/v4/[\\w]+(?:/[^/\\s?\"']+)?/(\\w+)|/repos/[^/\\s]+/[^/\\s]+/(\\w+)|/wiki/api/v2/\\w+|/rest/api/content)");

    /** Les outils dont une lecture est une commande d'état. */
    static final java.util.Set<String> STATE_TOOLS = java.util.Set.of("jira", "acli", "glab", "gh", "kubectl", "oc",
            "helm", "argocd", "aws", "az", "terraform", "psql", "liquibase", "flyway", "git", "curl",
            "invoke-restmethod", "irm", "invoke-webrequest");

    private final List<StateExtractor> extractors;

    public StateExtractors() {
        this(defaults());
    }

    StateExtractors(List<StateExtractor> extractors) {
        this.extractors = List.copyOf(extractors);
    }

    /** Lot 1 (SF-247-04) ; le lot 2 (SF-247-05) s'y ajoute. */
    static List<StateExtractor> defaults() {
        List<StateExtractor> all = new ArrayList<>();
        all.add(new TicketExtractors.Jira());
        all.add(new TicketExtractors.Confluence());
        all.add(new ReviewExtractors.GitLabMr());
        all.add(new ReviewExtractors.GitHubPr());
        all.add(new CiExtractors.GitLabPipelines());
        all.add(new CiExtractors.GitLabJobs());
        all.add(new CiExtractors.GitHubRuns());
        return all;
    }

    /** Le résultat d'une sortie : la forme de la commande, si c'est une commande d'état, et les faits lus. */
    public record Result(String form, boolean stateCommand, boolean usable, List<StateExtractor.Extracted> facts) {
    }

    /** Applique les extracteurs qui reconnaissent la commande à une sortie {@code bash}. Jamais d'exception. */
    public Result extract(String blockCommand, String output) {
        BashOutput out = BashOutput.parse(blockCommand, output);
        String command = out.command();
        String form = form(command);
        boolean state = form != null;
        if (!state || !out.usable()) {
            return new Result(form, state, out.usable(), List.of());
        }
        List<StateExtractor.Extracted> facts = new ArrayList<>();
        for (StateExtractor e : extractors) {
            try {
                if (e.accepts(command)) {
                    facts.addAll(e.extract(command, out.body()));
                }
            } catch (RuntimeException ignored) {
                // un extracteur en défaut ne fait rien écrire (en cas de doute, rien)
            }
        }
        return new Result(form, true, true, List.copyOf(facts));
    }

    /**
     * La forme d'une commande d'état ({@code null} sinon) : l'outil et son sous-verbe, ou pour une API le chemin
     * générique ({@code curl /rest/api/2/issue}). Les commandes enchaînées ({@code |}, {@code &&}) sont lues par leur
     * premier membre ; une écriture ({@code -X POST}, {@code apply}, {@code delete}…) n'est jamais une lecture d'état.
     */
    static String form(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String first = command.strip().split("\\s*(\\|\\||&&|;|\\|)\\s*", 2)[0];
        Matcher m = HEAD.matcher(first);
        if (!m.find()) {
            return null;
        }
        String tool = m.group(1).toLowerCase(Locale.ROOT);
        if (tool.endsWith(".exe")) {
            tool = tool.substring(0, tool.length() - 4);
        }
        if (!STATE_TOOLS.contains(tool) || writes(first)) {
            return null;
        }
        if (tool.equals("curl") || tool.startsWith("invoke-") || tool.equals("irm")) {
            Matcher api = API_PATH.matcher(first);
            if (!api.find()) {
                return null;
            }
            String path = api.group(1);
            return "curl " + (path.startsWith("/rest/api/") ? path : path.replaceAll("/\\d+", "/N"));
        }
        String verb = sub(m.group(2));
        if (tool.equals("git")) {
            return verb != null && List.of("status", "log", "branch", "describe", "rev-parse").contains(verb)
                    ? "git " + verb : null;
        }
        String verb2 = sub(m.group(3));
        return tool + (verb == null ? "" : " " + verb) + (verb2 == null || tool.equals("kubectl")
                || tool.equals("oc") ? "" : " " + verb2);
    }

    private static String sub(String word) {
        if (word == null || word.startsWith("-") || word.startsWith("\"") || word.startsWith("'")
                || !word.matches("[a-z][a-z\\-]{1,30}")) {
            return null;
        }
        return word;
    }

    /** Une commande qui écrit : jamais une lecture d'état. */
    static boolean writes(String command) {
        String c = " " + command.toLowerCase(Locale.ROOT) + " ";
        return c.matches(".*\\s-x\\s*(post|put|patch|delete)\\b.*") || c.contains("--request post")
                || c.contains("--request put") || c.contains("--request delete")
                || c.matches(".*\\s(apply|delete|create|merge|push|commit|reset|rm|destroy|import|upgrade|install|"
                        + "rollback|sync|scale|patch|edit|set|close|approve|update)\\s.*");
    }
}
