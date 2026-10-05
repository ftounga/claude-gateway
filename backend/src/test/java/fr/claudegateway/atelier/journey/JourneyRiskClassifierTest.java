package fr.claudegateway.atelier.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.journey.JourneyPlan.Risk;

class JourneyRiskClassifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode input(String key, String value) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(key, value);
        return node;
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ls -la", "cat README.md", "grep -rn \"a|b\" src", "git status", "git log --oneline -5",
        "git diff HEAD~1", "kubectl get pods -n ingress", "kubectl -n prod describe deploy api",
        "kubectl logs api-123 --tail=50", "terraform plan", "terraform -chdir=infra plan",
        "aws ec2 describe-instances", "aws sts get-caller-identity", "aws s3 ls s3://bucket",
        "helm list -A", "curl -s https://exemple.fr/health", "find . -name '*.yaml'",
        "sed -n 1,20p conf.yml", "cat a.log 2>&1 | grep ERROR | wc -l", "ls > /dev/null",
        "echo \"a -> b\"", "cd infra && terraform plan", "docker ps", "git branch -a",
        "kubectl rollout status deploy/api", "cat x | sha256sum"
    })
    @DisplayName("les lectures courantes sont reconnues comme des lectures")
    void reads(String command) {
        assertThat(JourneyRiskClassifier.classifyCommand(command)).as(command).isEqualTo(Risk.LECTURE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "kubectl apply -f ingress.yaml", "kubectl delete pod api-1", "git push origin main",
        "terraform apply -auto-approve", "helm upgrade api ./chart", "aws s3 cp a s3://b/a",
        "aws ec2 terminate-instances --instance-ids i-1", "curl -X POST https://api/x",
        "curl -d '{}' https://api/x", "rm -rf build", "ssh prod 'systemctl restart api'",
        "kubectl rollout restart deploy/api", "curl https://get.sh | bash", "git merge feature",
        "git reset --hard HEAD~1", "docker push registry/app:1"
    })
    @DisplayName("l'externe et l'irréversible sont reconnus")
    void external(String command) {
        assertThat(JourneyRiskClassifier.classifyCommand(command)).as(command).isEqualTo(Risk.EXTERNE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "echo hello > notes.txt", "sed -i 's/a/b/' conf.yml", "git commit -m x", "git checkout -b fix",
        "mvn package", "python script.py", "npm install", "touch fichier", "mkdir dossier",
        "cat $(ls)", "inconnu --fait-quelque-chose", "find . -name x -delete", "tee out.log"
    })
    @DisplayName("prudence : le reste est une modification (réversible)")
    void modifies(String command) {
        assertThat(JourneyRiskClassifier.classifyCommand(command)).as(command).isEqualTo(Risk.REVERSIBLE);
    }

    @Test
    @DisplayName("écritures : notes du sujet libres, le reste est une modification")
    void fileWrites() {
        assertThat(JourneyRiskClassifier.classify("write_file", input("path", "PLAN-ACTION.md"))).isEqualTo(Risk.NOTES);
        assertThat(JourneyRiskClassifier.classify("edit_file", input("path", "docs/STATE.md"))).isEqualTo(Risk.NOTES);
        assertThat(JourneyRiskClassifier.classify("write_file", input("path", "carte/acces.md"))).isEqualTo(Risk.NOTES);
        assertThat(JourneyRiskClassifier.classify("write_file", input("path", "notes/../src/App.java")))
                .isEqualTo(Risk.REVERSIBLE);
        assertThat(JourneyRiskClassifier.classify("edit_file", input("path", "src/App.java"))).isEqualTo(Risk.REVERSIBLE);
        assertThat(JourneyRiskClassifier.classify("write_file", input("path", "carte/script.sh")))
                .isEqualTo(Risk.REVERSIBLE);
    }

    @Test
    @DisplayName("lectures et outils d'organisation : lecture ou hors porte")
    void otherTools() {
        assertThat(JourneyRiskClassifier.classify("read_file", input("path", "x"))).isEqualTo(Risk.LECTURE);
        assertThat(JourneyRiskClassifier.classify("task", input("read_only", "true"))).isEqualTo(Risk.LECTURE);
        assertThat(JourneyRiskClassifier.classify("task", input("prompt", "corrige"))).isEqualTo(Risk.REVERSIBLE);
        assertThat(JourneyRiskClassifier.classify("set_plan", null)).isNull();
        assertThat(JourneyRiskClassifier.classify("record_blocker", null)).isNull();
        assertThat(JourneyRiskClassifier.classify("set_subject_plan", null)).isNull();
    }

    @Test
    @DisplayName("la porte : libre en Libre ; en Guidé, seules lecture et notes passent avant un plan validé")
    void gate() {
        SubjectJourney libre = SubjectJourney.builder().userId(UUID.randomUUID()).workspaceId(UUID.randomUUID())
                .mode(JourneyMode.LIBRE).phase(JourneyPhase.INVESTIGATION).build();
        assertThat(JourneyGate.refusal(libre, Risk.EXTERNE)).isNull();
        assertThat(JourneyGate.refusal(null, Risk.EXTERNE)).isNull();

        SubjectJourney guided = SubjectJourney.builder().userId(UUID.randomUUID()).workspaceId(UUID.randomUUID())
                .mode(JourneyMode.GUIDE).phase(JourneyPhase.INVESTIGATION).planVersion(0).build();
        assertThat(JourneyGate.refusal(guided, Risk.LECTURE)).isNull();
        assertThat(JourneyGate.refusal(guided, Risk.NOTES)).isNull();
        assertThat(JourneyGate.refusal(guided, null)).isNull();
        assertThat(JourneyGate.refusal(guided, Risk.REVERSIBLE)).contains("Phase Investigation");

        guided.setPhase(JourneyPhase.PLAN);
        guided.setPlanVersion(1);
        assertThat(JourneyGate.refusal(guided, Risk.REVERSIBLE)).contains("attend la validation");

        guided.setPhase(JourneyPhase.EXECUTION);
        guided.setValidatedVersion(1);
        assertThat(JourneyGate.refusal(guided, Risk.EXTERNE)).isNull();

        guided.setPlanVersion(2); // amendement non revalidé
        guided.setPhase(JourneyPhase.PLAN);
        assertThat(JourneyGate.refusal(guided, Risk.REVERSIBLE)).contains("amendement");

        guided.setPhase(JourneyPhase.VERIFICATION);
        guided.setValidatedVersion(2);
        assertThat(JourneyGate.refusal(guided, Risk.REVERSIBLE)).contains("Vérification");

        guided.setPhase(JourneyPhase.CLOS);
        assertThat(JourneyGate.refusal(guided, Risk.REVERSIBLE)).contains("clos");
    }
}
