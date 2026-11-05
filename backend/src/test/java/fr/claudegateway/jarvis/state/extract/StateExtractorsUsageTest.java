package fr.claudegateway.jarvis.state.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-247 / SF-247-17 — l'usage réel du PO (motifs anonymisés tirés de l'échantillon de prod) : alias de binaires,
 * préfixes muets, scripts maison à sortie JSON, et le tri des sorties pour les constats sur l'historique.
 */
class StateExtractorsUsageTest {

    private final StateExtractors extractors = new StateExtractors();

    private static String out(String command, String body) {
        return "$ " + command + "\n" + body + "\n[code de sortie: 0]";
    }

    private List<StateExtractor.Extracted> facts(String command, String body) {
        return extractors.extract(command, out(command, body)).facts();
    }

    private static String fact(List<StateExtractor.Extracted> facts, String key, String attribute) {
        return facts.stream().filter(f -> f.key().equals(key) && f.attribute().equals(attribute))
                .map(StateExtractor.Extracted::value).findFirst().orElse(null);
    }

    @Test
    @DisplayName("Alias résolus : A=~/aws-cli/aws, K=…/kubectl-devtools.sh ; préfixes cd/export/timeout retirés")
    void aliases() {
        assertThat(CommandShape.resolve("cd ~/dev && A=~/aws-cli/aws; export AWS_PROFILE=x-dev; $A ecs "
                + "describe-services --cluster c --services api")).isEqualTo("aws ecs describe-services --cluster c "
                + "--services api");
        assertThat(CommandShape.resolve("cd /u/dev && K=ops/kubectl-devtools.sh; timeout 60 $K get deploy -n app -o json"))
                .isEqualTo("kubectl get deploy -n app -o json");
        assertThat(CommandShape.resolve("set -e; K=\"ops/kubectl-devtools.sh\"; ${K} get pods"))
                .isEqualTo("kubectl get pods");
        // Plusieurs instructions qui écrivent : sorties mêlées ⇒ rien n'est résolu (en cas de doute, rien).
        String mixed = "A=~/aws-cli/aws; date -u +%T; $A eks describe-cluster --name c";
        assertThat(CommandShape.resolve(mixed)).isEqualTo(mixed);
        // Un alias qui n'est pas un binaire connu n'est pas résolu.
        assertThat(CommandShape.resolve("J=\"python3 /u/.atelier/jira/jira.py\"; $J voir PROJ-1"))
                .isEqualTo("python3 /u/.atelier/jira/jira.py voir PROJ-1");

        String cmd = "cd ~/dev && A=~/aws-cli/aws; $A sts get-caller-identity";
        List<StateExtractor.Extracted> f = facts(cmd, "{\"UserId\":\"AROAX:me\",\"Account\":\"111122223333\","
                + "\"Arn\":\"arn:aws:sts::111122223333:assumed-role/PowerUser/me@ex.com\"}");
        assertThat(fact(f, "AWS:111122223333", "identity_type")).isEqualTo("assumed-role");
    }

    @Test
    @DisplayName("JSON générique : script maison (identité + status/state/version/url) ; jamais une commande de fichiers")
    void genericJson() {
        String py = "cd ~/dev && python3 .outils/etat.py --json";
        List<StateExtractor.Extracted> f = facts(py, "[{\"name\":\"tgw-paris\",\"state\":\"available\"},"
                + "{\"name\":\"zone-share\",\"status\":\"ACTIVE\",\"url\":\"https://ex.com/z\"},{\"id\":42,"
                + "\"status\":\"x\"}]");
        assertThat(fact(f, "JSON:tgw-paris", "state")).isEqualTo("available");
        assertThat(fact(f, "JSON:zone-share", "status")).isEqualTo("ACTIVE");
        assertThat(fact(f, "JSON:zone-share", "url")).isEqualTo("https://ex.com/z");
        assertThat(f).noneMatch(x -> x.key().equals("JSON:42")); // une identité sans lettre n'est pas un objet
        assertThat(f).allMatch(x -> x.confidence().equals("MOYENNE") && x.kind().equals("OTHER"));
        // Une commande de fichiers ne lit pas d'état : rien.
        assertThat(facts("cat package.json", "{\"name\":\"front\",\"version\":\"1.0.0\"}")).isEmpty();
        // Sortie mêlée (pas un JSON entier) : rien.
        assertThat(facts(py, "13:02:11\n{\"name\":\"a\",\"status\":\"ok\"}")).isEmpty();
        // Un secret n'est jamais lu, même en JSON.
        assertThat(facts(py, "{\"name\":\"svc\",\"status\":\"glpat-abcdefghijklmnopqrstu\"}")).isEmpty();
        // Un script maison sans JSON n'est pas une commande d'état (couverture inchangée).
        StateExtractors.Result r = extractors.extract(py, out(py, "PROJ-1 | En revue"));
        assertThat(r.stateCommand()).isFalse();
        assertThat(r.facts()).isEmpty();
    }

    @Test
    @DisplayName("Commande sur plusieurs lignes : son écho entier n'est pas pris pour la sortie")
    void multiLineEcho() {
        String cmd = "python3 - <<'EOF'\nimport json\nprint(json.dumps({'name':'api','status':'up'}))\nEOF";
        BashOutput o = BashOutput.parse(cmd, out(cmd, "{\"name\": \"api\", \"status\": \"up\"}"));
        assertThat(o.command()).isEqualTo(cmd);
        assertThat(o.body()).isEqualTo("{\"name\": \"api\", \"status\": \"up\"}");
        assertThat(fact(facts(cmd, "{\"name\": \"api\", \"status\": \"up\"}"), "JSON:api", "status")).isEqualTo("up");
    }

    @Test
    @DisplayName("Tri des constats : vide, couverte, fichiers, secret, trop courte, trop longue ; retenue sinon")
    void selector() {
        String py = "python3 .outils/jira.py voir PROJ-7";
        String body = "PROJ-7 | Migration SSO | En revue | assigné à M. Martin";
        assertThat(classify(py, body).kept()).isTrue();
        assertThat(classify(py, body).fingerprint()).isEqualTo(HistoryNotesSelector.fingerprint(body));
        assertThat(classify(py, "").reason()).isEqualTo("vide");
        assertThat(classify("grep -n status src/App.java | head", body).reason()).isEqualTo("fichiers");
        assertThat(classify("cd x && cat a.txt; ls -l", body).reason()).isEqualTo("fichiers");
        assertThat(classify("printf 'host=h\\n' | git credential fill", "password=s3cr3tvalue").reason())
                .isEqualTo("secret");
        assertThat(classify("security find-generic-password -s x -w", "hunter2hunter2hunter2hunter2").reason())
                .isEqualTo("secret");
        assertThat(classify("kubectl get secret db -o yaml", "data:\n  password: c2VjcmV0c2VjcmV0").reason())
                .isEqualTo("secret");
        // Lecture d'identifiant capturée dans une variable : non affichée, la sortie (l'état) reste lisible — et la
        // commande se résout (H= substituée) : la lecture GitLab est couverte gratuitement.
        String api = "H=forge.ex; T=$(printf 'host=%s\\n' $H | git credential fill | sed -n 's/^password=//p'); "
                + "curl -s -H \"X: $T\" \"https://$H/api/v4/projects/3/merge_requests/9\"";
        String mr = "{\"iid\":9,\"title\":\"Fix\",\"state\":\"opened\",\"web_url\":\"https://forge.ex/g/p/-/merge_requests/9\"}";
        assertThat(classify(api, mr).reason()).isEqualTo("couverte");
        String other = "T=$(printf 'host=h\\n' | git credential fill | sed -n 's/^password=//p'); curl -s -H \"X: $T\" "
                + "https://forge.ex/api/v4/projects/3/repository/files/x";
        assertThat(classify(other, "contenu du fichier lu par l'API, sans état reconnu").kept()).isTrue();
        assertThat(classify(py, "Bearer abcdefghijklmnopqrstuvwxyz0123456789").reason()).isEqualTo("secret");
        assertThat(classify(py, "ok").reason()).isEqualTo("trop courte");
        assertThat(classify(py, "x".repeat(StateNotesGuard.MAX_PER_OUTPUT + 1)).reason()).isEqualTo("trop longue");
        String jira = "curl -s https://jira.ex/rest/api/2/issue/PROJ-1";
        String jiraBody = "{\"key\":\"PROJ-1\",\"fields\":{\"status\":{\"name\":\"Ouvert\"}}}";
        assertThat(HistoryNotesSelector.classify(jira, out(jira, jiraBody), extractors.extract(jira, out(jira,
                jiraBody))).reason()).isEqualTo("couverte");
        String failed = "$ " + py + "\n" + body + "\n[code de sortie: 1]";
        assertThat(HistoryNotesSelector.classify(py, failed, extractors.extract(py, failed)).reason())
                .isEqualTo("inexploitable");
    }

    @Test
    @DisplayName("Regroupement par tour : sorties entières, au plus MAX_GROUP_CHARS par appel, dans l'ordre")
    void grouping() {
        List<HistoryNotesSelector.Candidate> c = List.of(cand("a", 5_000), cand("b", 5_000), cand("c", 5_000),
                cand("d", 100));
        List<List<HistoryNotesSelector.Candidate>> g = HistoryNotesSelector.group(c,
                HistoryNotesSelector.MAX_GROUP_CHARS);
        assertThat(g).hasSize(2);
        assertThat(g.get(0)).extracting(HistoryNotesSelector.Candidate::toolUseId).containsExactly("a", "b");
        assertThat(g.get(1)).extracting(HistoryNotesSelector.Candidate::toolUseId).containsExactly("c", "d");
    }

    private static HistoryNotesSelector.Candidate cand(String id, int size) {
        String body = "y".repeat(size);
        return new HistoryNotesSelector.Candidate(id, "python3 x.py", body, HistoryNotesSelector.fingerprint(body),
                null);
    }

    private HistoryNotesSelector.Verdict classify(String command, String body) {
        String o = out(command, body);
        return HistoryNotesSelector.classify(command, o, extractors.extract(command, o));
    }
}
