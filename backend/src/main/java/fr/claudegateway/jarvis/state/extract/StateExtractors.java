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
        // SF-247-05 — lot 2 : déploiements, cloud, infra, base, git ; identités ACCESS (amendement F-248 D14).
        all.add(new DeployExtractors.KubeJson());
        all.add(new DeployExtractors.KubeTable());
        all.add(new DeployExtractors.KubeRollout());
        all.add(new DeployExtractors.Helm());
        all.add(new DeployExtractors.Argo());
        all.add(new CloudExtractors.Aws());
        all.add(new CloudExtractors.Az());
        all.add(new CloudExtractors.Terraform());
        all.add(new RepoExtractors.Git());
        all.add(new RepoExtractors.SchemaVersion());
        all.add(new AccessExtractors.AwsIdentity());
        all.add(new AccessExtractors.AzAccount());
        all.add(new AccessExtractors.KubeContext());
        all.add(new AccessExtractors.CliAuth());
        return all;
    }

    /** SF-247-05 — le repli des API génériques, appliqué seulement si aucun extracteur spécialisé n'a couvert. */
    private static final StateExtractor FALLBACK = new GenericApiExtractor();
    /** SF-247-17 — le repli des sorties JSON génériques (scripts maison, outils derrière un alias). */
    private static final StateExtractor JSON = new GenericJsonExtractor();

    /** Le résultat d'une sortie : la forme de la commande, si c'est une commande d'état, et les faits lus. */
    public record Result(String form, boolean stateCommand, boolean usable, List<StateExtractor.Extracted> facts) {
    }

    /** Applique les extracteurs qui reconnaissent la commande à une sortie {@code bash}. Jamais d'exception. */
    public Result extract(String blockCommand, String output) {
        BashOutput out = BashOutput.parse(blockCommand, output);
        // SF-247-17 — l'usage réel : « cd … && A=~/aws-cli/aws; $A ecs … » ⇒ « aws ecs … » (une seule instruction utile,
        // alias de binaire résolu) ; sinon la première ligne, comme avant.
        String full = out.command();
        String resolved = CommandShape.resolve(full);
        String command = resolved == null || resolved.equals(full) ? firstLine(full) : resolved;
        String form = form(command);
        boolean state = form != null;
        if (!out.usable()) {
            return new Result(form, state, false, List.of());
        }
        if (!state) {
            // SF-247-17 — script maison ou outil non reconnu : seule une sortie JSON générique d'état est lue (repli).
            return new Result(null, false, true, clean(json(full, out.body())));
        }
        List<StateExtractor.Extracted> facts = new ArrayList<>();
        boolean accepted = false;
        for (StateExtractor e : extractors) {
            try {
                if (e.accepts(command)) {
                    accepted = true;
                    facts.addAll(e.extract(command, out.body()));
                }
            } catch (RuntimeException ignored) {
                // un extracteur en défaut ne fait rien écrire (en cas de doute, rien)
            }
        }
        if (!accepted && FALLBACK.accepts(command)) {
            try {
                facts.addAll(FALLBACK.extract(command, out.body()));
            } catch (RuntimeException ignored) {
                // rien
            }
        }
        if (facts.isEmpty() && !accepted) {
            facts.addAll(json(full, out.body()));
        }
        return new Result(form, true, true, clean(facts));
    }

    /** SF-247-17 — le repli JSON générique (identité + champ d'état), jamais sur une commande de fichiers. */
    private static List<StateExtractor.Extracted> json(String command, String body) {
        try {
            return JSON.accepts(command) ? JSON.extract(command, body) : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** SF-247-05 — « aucun secret » : toute valeur qui ressemble à un secret est rejetée, quel que soit l'extracteur. */
    private static List<StateExtractor.Extracted> clean(List<StateExtractor.Extracted> facts) {
        List<StateExtractor.Extracted> out = new ArrayList<>(facts);
        out.removeIf(f -> SecretFilter.looksSecret(f.value()) || SecretFilter.looksSecret(f.key()));
        return List.copyOf(out);
    }

    private static String firstLine(String command) {
        return command == null ? null : command.strip().split("\\n", 2)[0];
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
                // SF-247-05 — API générique : la forme est son premier segment de chemin (« curl /health »).
                Matcher url = Pattern.compile("https?://[^/\\s\"']+(/[\\w.\\-]*)?").matcher(first);
                return url.find() ? "curl " + (url.group(1) == null ? "/" : url.group(1)) : null;
            }
            String path = api.group(1);
            return "curl " + (path.startsWith("/rest/api/") ? path : path.replaceAll("/\\d+", "/N"));
        }
        String verb = sub(m.group(2));
        if (tool.equals("git")) {
            Matcher g = Pattern.compile("\\bgit\\s+(?:-C\\s+(?:\"[^\"]+\"|\\S+)\\s+)?([a-z\\-]+)").matcher(first);
            verb = g.find() ? g.group(1) : null;
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
