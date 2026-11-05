package fr.claudegateway.atelier.veille;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** F-247 / SF-247-08 — un relevé n'admet que des verbes de lecture, refusé à l'enregistrement sinon. */
class JarvisWatchStateVerbsTest {

    private static final List<String> READS = List.of(
            "kubectl get deploy -n prod -o json",
            "kubectl --context prod rollout status deploy/api",
            "oc describe dc/api",
            "helm list -A -o json",
            "helm status api -n prod",
            "argocd app get api -o json",
            "glab mr list -R grp/app -F json",
            "gh pr list --json state",
            "gh run view 42",
            "aws ecs describe-services --cluster c --services s",
            "aws --profile ro lambda get-function --function-name f",
            "az webapp show -n x -g y",
            "terraform output -json",
            "terraform state list",
            "git -C ../app status",
            "git log -n 5 --oneline",
            "jira issue list --plain",
            "psql -c 'select id from databasechangelog order by dateexecuted desc limit 1'",
            "bash .atelier/releve/tickets.sh");

    private static final List<String> WRITES = List.of(
            "kubectl delete pod api-1",
            "kubectl rollout restart deploy/api",
            "kubectl apply -f x.yaml",
            "helm upgrade api ./chart",
            "argocd app sync api",
            "glab mr merge 3",
            "gh pr merge 17",
            "aws ecs update-service --cluster c",
            "az webapp restart -n x -g y",
            "terraform plan",
            "terraform apply -auto-approve",
            "terraform state rm aws_s3_bucket.b",
            "git push origin main",
            "psql -c 'delete from lots'",
            "kubectl get pods; kubectl delete pod x");

    @Test
    void readingVerbsPass() {
        for (String c : READS) {
            assertThat(JarvisWatchProbeGuard.stateVerbProblem(c, "la commande")).as(c).isEmpty();
        }
    }

    @Test
    void anyOtherVerbIsRefused() {
        for (String c : WRITES) {
            assertThat(JarvisWatchProbeGuard.stateVerbProblem(c, "la commande")).as(c).isPresent();
        }
    }

    @Test
    void theScriptIsReadLineByLine() {
        String script = "#!/bin/sh\nset -e\nkubectl get deploy -o json\nkubectl scale deploy/api --replicas=0\n";
        assertThat(JarvisWatchProbeGuard.stateVerbProblem(script, "le script")).isPresent();
    }
}
