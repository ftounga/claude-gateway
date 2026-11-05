package fr.claudegateway.jarvis.state.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-247 / SF-247-04 — extracteurs purs du lot 1 (tickets, pages, revues, CI), sortie bash, formes, écritures. */
class StateExtractorsLot1Test {

    private final StateExtractors extractors = new StateExtractors();

    private static String out(String command, String body) {
        return "$ " + command + "\n" + body + "\n[code de sortie: 0]";
    }

    private static String fact(List<StateExtractor.Extracted> facts, String key, String attribute) {
        return facts.stream().filter(f -> f.key().equals(key) && f.attribute().equals(attribute))
                .map(StateExtractor.Extracted::value).findFirst().orElse(null);
    }

    @Test
    @DisplayName("sortie bash : code 0 et entière seulement ; tronquée, en échec ou sans code ⇒ inexploitable")
    void bashOutput() {
        assertThat(BashOutput.parse("x", out("git status", "On branch main")).usable()).isTrue();
        assertThat(BashOutput.parse("x", out("git status", "On branch main")).command()).isEqualTo("git status");
        assertThat(BashOutput.parse("x", "$ jira\n{}\n[code de sortie: 1]").usable()).isFalse();
        assertThat(BashOutput.parse("x", "… (début tronqué)\n\"x\"}\n[code de sortie: 0]").usable()).isFalse();
        assertThat(BashOutput.parse("x", "$ a\n{\n… (sortie tronquée)\n[code de sortie: 0]").usable()).isFalse();
        assertThat(BashOutput.parse("x", "$ a\nhello").usable()).isFalse();
    }

    @Test
    @DisplayName("Jira : un ticket et une recherche, statut / assigné / titre / priorité / bloqué par")
    void jira() {
        String cmd = "curl -s -H \"Authorization: Bearer $TOKEN\" https://jira.ex/rest/api/2/issue/PROJ-123";
        String body = "{\"key\":\"PROJ-123\",\"fields\":{\"summary\":\"Migration SSO\",\"status\":{\"name\":\"En revue\"},"
                + "\"assignee\":{\"displayName\":\"Rémi Dupont\"},\"priority\":{\"name\":\"Haute\"},"
                + "\"updated\":\"2026-11-04T17:42:00.000+0100\",\"issuelinks\":[{\"type\":{\"inward\":\"is blocked by\"},"
                + "\"inwardIssue\":{\"key\":\"PROJ-99\"}}]}}";
        StateExtractors.Result r = extractors.extract(cmd, out(cmd, body));
        assertThat(r.form()).isEqualTo("curl /rest/api/2/issue");
        assertThat(fact(r.facts(), "JIRA:PROJ-123", "status")).isEqualTo("En revue");
        assertThat(fact(r.facts(), "JIRA:PROJ-123", "assignee")).isEqualTo("Rémi Dupont");
        assertThat(fact(r.facts(), "JIRA:PROJ-123", "title")).isEqualTo("Migration SSO");
        assertThat(fact(r.facts(), "JIRA:PROJ-123", "blocked_by")).isEqualTo("PROJ-99");
        assertThat(r.facts()).allMatch(f -> f.confidence().equals("HAUTE") && f.kind().equals("TICKET"));

        String search = "curl -s 'https://jira.ex/rest/api/3/search?jql=assignee=currentUser()'";
        String list = "{\"issues\":[{\"key\":\"AB-1\",\"fields\":{\"status\":{\"name\":\"Fait\"}}},"
                + "{\"key\":\"BC-2\",\"fields\":{\"status\":{\"name\":\"Ouvert\"}}},{\"key\":\"nope\",\"fields\":{}}]}";
        StateExtractors.Result s = extractors.extract(search, out(search, list));
        assertThat(fact(s.facts(), "JIRA:AB-1", "status")).isEqualTo("Fait");
        assertThat(fact(s.facts(), "JIRA:BC-2", "status")).isEqualTo("Ouvert");
        assertThat(s.facts()).hasSize(2);
    }

    @Test
    @DisplayName("Confluence : titre, version, url reconstituée de la sortie (base + webui)")
    void confluence() {
        String cmd = "curl -s https://wiki.ex/rest/api/content/123456?expand=version";
        String body = "{\"id\":\"123456\",\"type\":\"page\",\"status\":\"current\",\"title\":\"Runbook\","
                + "\"version\":{\"number\":7,\"when\":\"2026-11-03T10:00:00Z\"},"
                + "\"_links\":{\"base\":\"https://wiki.ex\",\"webui\":\"/display/X/Runbook\"}}";
        StateExtractors.Result r = extractors.extract(cmd, out(cmd, body));
        assertThat(fact(r.facts(), "CONFLUENCE:123456", "page_version")).isEqualTo("7");
        assertThat(fact(r.facts(), "CONFLUENCE:123456", "url")).isEqualTo("https://wiki.ex/display/X/Runbook");
        assertThat(r.facts()).allMatch(f -> f.kind().equals("PAGE"));
    }

    @Test
    @DisplayName("MR GitLab : clé tirée de references.full, pipeline, conflits, url ; liste")
    void gitlabMr() {
        String cmd = "glab mr view 42 -F json";
        String body = "{\"iid\":42,\"state\":\"opened\",\"title\":\"Feat SSO\",\"target_branch\":\"main\","
                + "\"source_branch\":\"feat/sso\",\"has_conflicts\":false,\"detailed_merge_status\":\"not_approved\","
                + "\"head_pipeline\":{\"status\":\"failed\"},\"references\":{\"full\":\"grp/proj!42\"},"
                + "\"web_url\":\"https://gitlab.ex/grp/proj/-/merge_requests/42\"}";
        StateExtractors.Result r = extractors.extract(cmd, out(cmd, body));
        assertThat(r.form()).isEqualTo("glab mr view");
        assertThat(fact(r.facts(), "GITLAB:grp/proj!42", "status")).isEqualTo("opened");
        assertThat(fact(r.facts(), "GITLAB:grp/proj!42", "pipeline")).isEqualTo("failed");
        assertThat(fact(r.facts(), "GITLAB:grp/proj!42", "conflicts")).isEqualTo("false");
        assertThat(fact(r.facts(), "GITLAB:grp/proj!42", "url"))
                .isEqualTo("https://gitlab.ex/grp/proj/-/merge_requests/42");

        String api = "curl -s https://gitlab.ex/api/v4/projects/12/merge_requests?state=opened";
        String list = "[{\"state\":\"opened\",\"web_url\":\"https://gitlab.ex/a/b/-/merge_requests/3\"},"
                + "{\"state\":\"merged\",\"web_url\":\"https://gitlab.ex/a/b/-/merge_requests/4\"}]";
        StateExtractors.Result l = extractors.extract(api, out(api, list));
        assertThat(fact(l.facts(), "GITLAB:a/b!3", "status")).isEqualTo("opened");
        assertThat(fact(l.facts(), "GITLAB:a/b!4", "status")).isEqualTo("merged");
    }

    @Test
    @DisplayName("PR GitHub : gh --json et API ; fusionnée ⇒ MERGED ; conflits")
    void githubPr() {
        String cmd = "gh pr view 17 --json number,state,title,url,reviewDecision,mergeable,headRefName,baseRefName";
        String body = "{\"number\":17,\"state\":\"OPEN\",\"title\":\"Fix\",\"url\":\"https://github.com/org/repo/pull/17\","
                + "\"reviewDecision\":\"APPROVED\",\"mergeable\":\"CONFLICTING\",\"headRefName\":\"fix\",\"baseRefName\":\"main\"}";
        StateExtractors.Result r = extractors.extract(cmd, out(cmd, body));
        assertThat(fact(r.facts(), "GITHUB:org/repo#17", "status")).isEqualTo("OPEN");
        assertThat(fact(r.facts(), "GITHUB:org/repo#17", "approvals")).isEqualTo("APPROVED");
        assertThat(fact(r.facts(), "GITHUB:org/repo#17", "conflicts")).isEqualTo("true");

        String api = "curl -s https://api.github.com/repos/org/repo/pulls/18";
        String pr = "{\"state\":\"closed\",\"merged_at\":\"2026-11-01T00:00:00Z\","
                + "\"html_url\":\"https://github.com/org/repo/pull/18\",\"url\":\"https://api.github.com/repos/org/repo/pulls/18\"}";
        assertThat(fact(extractors.extract(api, out(api, pr)).facts(), "GITHUB:org/repo#18", "status"))
                .isEqualTo("MERGED");
    }

    @Test
    @DisplayName("CI : pipelines GitLab (le plus récent par branche), job en échec, exécutions GitHub")
    void ci() {
        String cmd = "curl -s https://gitlab.ex/api/v4/projects/12/pipelines?ref=main";
        String body = "[{\"id\":9,\"status\":\"failed\",\"ref\":\"main\",\"sha\":\"abcdef1234567890\","
                + "\"web_url\":\"https://gitlab.ex/grp/proj/-/pipelines/9\"},{\"id\":8,\"status\":\"success\",\"ref\":\"main\","
                + "\"web_url\":\"https://gitlab.ex/grp/proj/-/pipelines/8\"}]";
        StateExtractors.Result r = extractors.extract(cmd, out(cmd, body));
        assertThat(fact(r.facts(), "PIPELINE:grp/proj@main", "result")).isEqualTo("failed");
        assertThat(fact(r.facts(), "PIPELINE:grp/proj@main", "commit")).isEqualTo("abcdef123456");

        String jobs = "curl -s https://gitlab.ex/api/v4/projects/12/pipelines/9/jobs";
        String jb = "[{\"status\":\"success\",\"name\":\"build\",\"stage\":\"build\",\"ref\":\"main\","
                + "\"web_url\":\"https://gitlab.ex/grp/proj/-/jobs/1\"},{\"status\":\"failed\",\"name\":\"it\",\"stage\":\"test\","
                + "\"ref\":\"main\",\"web_url\":\"https://gitlab.ex/grp/proj/-/jobs/2\"}]";
        assertThat(fact(extractors.extract(jobs, out(jobs, jb)).facts(), "PIPELINE:grp/proj@main", "failed_step"))
                .isEqualTo("test / it");

        String gh = "gh run list --json status,conclusion,headBranch,workflowName,url,headSha -L 5";
        String runs = "[{\"status\":\"completed\",\"conclusion\":\"success\",\"headBranch\":\"main\",\"workflowName\":\"CI\","
                + "\"url\":\"https://github.com/org/repo/actions/runs/77\",\"headSha\":\"0123456789abcdef\"}]";
        assertThat(fact(extractors.extract(gh, out(gh, runs)).facts(), "PIPELINE:org/repo@main CI", "result"))
                .isEqualTo("success");
    }

    @Test
    @DisplayName("formes d'état et écritures : une écriture n'est jamais une lecture ; texte inconnu ⇒ aucun fait")
    void formsAndWrites() {
        assertThat(StateExtractors.form("kubectl get deploy -n prod")).isEqualTo("kubectl get");
        assertThat(StateExtractors.form("git log -n 5 --oneline")).isEqualTo("git log");
        assertThat(StateExtractors.form("git push origin main")).isNull();
        assertThat(StateExtractors.form("ls -la")).isNull();
        assertThat(StateExtractors.form("glab mr merge 42")).isNull();
        assertThat(StateExtractors.form("curl -X POST https://jira.ex/rest/api/2/issue")).isNull();
        assertThat(StateExtractors.form("terraform apply -auto-approve")).isNull();
        assertThat(StateExtractors.form("curl -s https://jira.ex/rest/api/2/issue/PROJ-1 | jq .")).isEqualTo(
                "curl /rest/api/2/issue");
        String cmd = "jira issue view PROJ-1";
        StateExtractors.Result r = extractors.extract(cmd, out(cmd, "PROJ-1  En cours  Rémi"));
        assertThat(r.stateCommand()).isTrue();
        assertThat(r.facts()).isEmpty();
        // JSON illisible ⇒ rien
        String bad = "curl -s https://jira.ex/rest/api/2/issue/PROJ-2";
        assertThat(extractors.extract(bad, out(bad, "{\"key\":\"PROJ-2\",\"fields\":")).facts()).isEmpty();
    }
}
