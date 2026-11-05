package fr.claudegateway.jarvis.state.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-247 / SF-247-05 — extracteurs du lot 2 (déploiements, cloud, infra, base, git, API, identités) et « aucun secret ». */
class StateExtractorsLot2Test {

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
    @DisplayName("kubectl : JSON (image, réplicas, version), tableau strict, rollout ; contexte du drapeau")
    void kube() {
        String cmd = "kubectl --context prod get deploy api -n app -o json";
        String body = "{\"kind\":\"Deployment\",\"metadata\":{\"name\":\"api\",\"namespace\":\"app\",\"labels\":"
                + "{\"app.kubernetes.io/version\":\"2.14.1\"}},\"spec\":{\"replicas\":3,\"template\":{\"spec\":"
                + "{\"containers\":[{\"image\":\"reg/api:2.14.1\"}]}}},\"status\":{\"readyReplicas\":2}}";
        List<StateExtractor.Extracted> f = facts(cmd, body);
        assertThat(fact(f, "K8S:prod/app/deployment/api", "image")).isEqualTo("reg/api:2.14.1");
        assertThat(fact(f, "K8S:prod/app/deployment/api", "replicas")).isEqualTo("2/3");
        assertThat(fact(f, "K8S:prod/app/deployment/api", "version")).isEqualTo("2.14.1");

        String table = "NAME   READY   UP-TO-DATE   AVAILABLE   AGE   CONTAINERS   IMAGES          SELECTOR\n"
                + "web    1/1     1            1           3d    web          reg/web:1.2.0   app=web";
        List<StateExtractor.Extracted> t = facts("kubectl get deploy -n front -o wide", table);
        assertThat(fact(t, "K8S:front/deployment/web", "replicas")).isEqualTo("1/1");
        assertThat(fact(t, "K8S:front/deployment/web", "image")).isEqualTo("reg/web:1.2.0");
        assertThat(facts("kubectl get deploy -n front", "something else\nweb 1/1")).isEmpty();

        assertThat(fact(facts("kubectl rollout status deployment/api -n app",
                "deployment \"api\" successfully rolled out"), "K8S:app/deployment/api", "status")).isNotNull();
    }

    @Test
    @DisplayName("helm list / history, argocd app get")
    void helmArgo() {
        String list = "[{\"name\":\"api\",\"namespace\":\"app\",\"revision\":\"12\",\"status\":\"deployed\","
                + "\"chart\":\"api-1.4.0\",\"app_version\":\"2.14.1\"}]";
        List<StateExtractor.Extracted> f = facts("helm list -n app -o json", list);
        assertThat(fact(f, "HELM:app/api", "status")).isEqualTo("deployed");
        assertThat(fact(f, "HELM:app/api", "version")).isEqualTo("2.14.1");
        String hist = "[{\"revision\":11,\"status\":\"superseded\",\"chart\":\"api-1.3.0\",\"app_version\":\"2.13\"},"
                + "{\"revision\":12,\"status\":\"failed\",\"chart\":\"api-1.4.0\",\"app_version\":\"2.14.1\"}]";
        assertThat(fact(facts("helm history api -n app -o json", hist), "HELM:app/api", "status")).isEqualTo("failed");
        String argo = "{\"metadata\":{\"name\":\"api-prod\"},\"status\":{\"sync\":{\"status\":\"OutOfSync\","
                + "\"revision\":\"0123456789abcdef\"},\"health\":{\"status\":\"Degraded\"}}}";
        List<StateExtractor.Extracted> a = facts("argocd app get api-prod -o json", argo);
        assertThat(fact(a, "ARGO:api-prod", "sync")).isEqualTo("OutOfSync");
        assertThat(fact(a, "ARGO:api-prod", "health")).isEqualTo("Degraded");
    }

    @Test
    @DisplayName("aws (ECS, Lambda, CloudFormation), az show, terraform output (jamais sensible) et state list")
    void cloudInfra() {
        String ecs = "{\"services\":[{\"serviceArn\":\"arn:aws:ecs:eu-west-3:123456789012:service/c/api\","
                + "\"status\":\"ACTIVE\",\"desiredCount\":2,\"runningCount\":2,\"taskDefinition\":"
                + "\"arn:aws:ecs:eu-west-3:123456789012:task-definition/api:42\",\"deployments\":[{\"rolloutState\":\"COMPLETED\"}]}]}";
        List<StateExtractor.Extracted> e = facts("aws ecs describe-services --cluster c --services api", ecs);
        String k = "AWS:arn:aws:ecs:eu-west-3:123456789012:service/c/api";
        assertThat(fact(e, k, "replicas")).isEqualTo("2/2");
        assertThat(fact(e, k, "version")).isEqualTo("api:42");
        String lambda = "{\"Configuration\":{\"FunctionArn\":\"arn:aws:lambda:eu-west-3:123456789012:function:f\","
                + "\"Version\":\"$LATEST\",\"State\":\"Active\",\"LastUpdateStatus\":\"Successful\"}}";
        assertThat(fact(facts("aws lambda get-function --function-name f", lambda),
                "AWS:arn:aws:lambda:eu-west-3:123456789012:function:f", "state")).isEqualTo("Active");
        String cfn = "{\"Stacks\":[{\"StackId\":\"arn:aws:cloudformation:eu-west-3:123456789012:stack/s/x\","
                + "\"StackStatus\":\"UPDATE_COMPLETE\"}]}";
        assertThat(fact(facts("aws cloudformation describe-stacks --stack-name s", cfn),
                "AWS:arn:aws:cloudformation:eu-west-3:123456789012:stack/s/x", "status")).isEqualTo("UPDATE_COMPLETE");
        String aks = "{\"id\":\"/subscriptions/s/resourceGroups/rg/providers/Microsoft.ContainerService/managedClusters/k\","
                + "\"kubernetesVersion\":\"1.29.2\",\"provisioningState\":\"Succeeded\",\"powerState\":{\"code\":\"Running\"}}";
        List<StateExtractor.Extracted> z = facts("az aks show -g rg -n k", aks);
        String zk = "AZ:/subscriptions/s/resourceGroups/rg/providers/Microsoft.ContainerService/managedClusters/k";
        assertThat(fact(z, zk, "version")).isEqualTo("1.29.2");
        assertThat(fact(z, zk, "state")).isEqualTo("Running");

        String tf = "{\"api_url\":{\"sensitive\":false,\"type\":\"string\",\"value\":\"https://api.ex\"},"
                + "\"db_password\":{\"sensitive\":true,\"type\":\"string\",\"value\":\"hunter2\"}}";
        List<StateExtractor.Extracted> o = facts("terraform -chdir=infra/prod output -json", tf);
        assertThat(fact(o, "TF:infra/prod/output.api_url", "value")).isEqualTo("https://api.ex");
        assertThat(o).noneMatch(x -> x.value().contains("hunter2"));
        assertThat(fact(facts("terraform -chdir=infra/prod state list",
                "aws_s3_bucket.logs\nmodule.net.aws_vpc.main"), "TF:infra/prod", "resources"))
                .isEqualTo("2 ressources");
        assertThat(facts("terraform plan", "Plan: 1 to add")).isEmpty();
    }

    @Test
    @DisplayName("git status / branch -vv / log / describe ; version de schéma par psql")
    void gitAndDb() {
        String status = "On branch main\nYour branch is ahead of 'origin/main' by 2 commits.\n\nnothing to commit, "
                + "working tree clean";
        List<StateExtractor.Extracted> s = facts("git status", status);
        assertThat(fact(s, "GIT:{subject}@main", "ahead_behind")).isEqualTo("en avance de 2 sur origin/main");
        assertThat(fact(s, "GIT:{subject}@main", "state")).isEqualTo("propre");
        assertThat(fact(facts("git -C /home/me/repo-x branch -vv",
                "  dev  1111111 old\n* main abcdef1 [origin/main: behind 1] Fix login"), "GIT:repo-x@main",
                "ahead_behind")).isEqualTo("origin/main: behind 1");
        assertThat(fact(facts("git log -n 1 --oneline", "abcdef1 Fix login"), "GIT:{subject}@HEAD", "last_commit"))
                .isEqualTo("abcdef1 Fix login");
        assertThat(facts("git log origin/dev -n 1 --oneline", "abcdef1 Fix login")).isEmpty();
        assertThat(fact(facts("git describe --tags", "v2.14.1-3-gabcdef1"), "GIT:{subject}@HEAD", "tag"))
                .isEqualTo("v2.14.1-3-gabcdef1");

        String cmd = "psql -d app -c \"select id, dateexecuted from databasechangelog order by dateexecuted desc limit 1\"";
        String table = "        id         |      dateexecuted\n-------------------+------------------------\n"
                + " 247-04-coverage   | 2026-11-05 10:00:00\n(1 row)";
        assertThat(fact(facts(cmd, table), "DB:app/databasechangelog", "schema_version")).isEqualTo("247-04-coverage");
    }

    @Test
    @DisplayName("API générique (repli) ; identités ACCESS : compte, type, validité, état — jamais de jeton")
    void apiAndAccess() {
        List<StateExtractor.Extracted> a = facts("curl -s https://svc.ex/health?x=1", "{\"status\":\"UP\",\"version\":\"1.9\"}");
        assertThat(fact(a, "API:svc.ex/health", "status")).isEqualTo("UP");
        // l'API Jira reste lue par son extracteur, pas par le repli
        assertThat(facts("curl -s https://jira.ex/rest/api/2/issue/P-1",
                "{\"key\":\"P-1\",\"status\":\"x\",\"fields\":{\"status\":{\"name\":\"Fait\"}}}"))
                .noneMatch(x -> x.kind().equals("OTHER"));

        String sts = "{\"UserId\":\"AROAXXXXXXXXXXXXXXXXX:me\",\"Account\":\"123456789012\","
                + "\"Arn\":\"arn:aws:sts::123456789012:assumed-role/Dev/me\"}";
        List<StateExtractor.Extracted> s = facts("aws sts get-caller-identity", sts);
        assertThat(fact(s, "AWS:123456789012", "identity_type")).isEqualTo("assumed-role");
        assertThat(s).noneMatch(x -> x.value().contains("AROA"));
        assertThat(s).allMatch(x -> x.kind().equals("ACCESS"));

        String az = "{\"id\":\"11111111-2222-3333-4444-555555555555\",\"name\":\"Prod\",\"state\":\"Enabled\","
                + "\"user\":{\"name\":\"me@ex.com\",\"type\":\"user\"}}";
        assertThat(fact(facts("az account show", az), "AZ:11111111-2222-3333-4444-555555555555", "state"))
                .isEqualTo("Enabled");
        assertThat(fact(facts("kubectl config current-context", "prod-eks"), "K8S:current-context", "account"))
                .isEqualTo("prod-eks");
        String glab = "gitlab.ex\n  ✓ Logged in to gitlab.ex as rdupont (/home/me/.config/glab-cli/config.yml)\n"
                + "  ✓ Token: glpat-abcdefghijklmnopqrstu\n";
        List<StateExtractor.Extracted> g = facts("glab auth status", glab);
        assertThat(fact(g, "GITLAB:gitlab.ex", "account")).isEqualTo("rdupont");
        assertThat(fact(g, "GITLAB:gitlab.ex", "state")).isEqualTo("connecté");
        assertThat(g).noneMatch(x -> x.value().contains("glpat"));
        String gh = "github.com\n  ✓ Logged in to github.com account octo (keyring)\n  - Token: gho_************************************";
        assertThat(fact(facts("gh auth status", gh), "GITHUB:github.com", "account")).isEqualTo("octo");
    }

    @Test
    @DisplayName("corpus « aucun secret » : aucune valeur de type jeton ne sort, quel que soit l'extracteur")
    void noSecretEver() {
        String[] secrets = {"glpat-abcdefghijklmnop1234", "ghp_abcdefghijklmnopqrstuvwxyz0123", "AKIAABCDEFGHIJKLMNOP",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijk", "Bearer abcdefghijklmnopqrstuv",
            "sk-ant-abcdefghijklmnopqrstuvwxyz", "password=hunter2hunter2", "https://u:pa55word@host/x"};
        for (String secret : secrets) {
            String body = "{\"status\":\"" + secret + "\",\"version\":\"" + secret + "\"}";
            assertThat(facts("curl -s https://svc.ex/x", body)).as(secret).isEmpty();
            String jira = "{\"key\":\"P-1\",\"fields\":{\"status\":{\"name\":\"" + secret + "\"},\"summary\":\"ok\"}}";
            assertThat(facts("curl -s https://jira.ex/rest/api/2/issue/P-1", jira))
                    .as(secret).noneMatch(x -> x.value().contains(secret.substring(0, 6)) && x.attribute().equals("status"));
        }
    }
}
