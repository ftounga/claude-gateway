package fr.claudegateway.jarvis.state.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.shared.jobs.AsyncJobHandler;
import fr.claudegateway.shared.jobs.AsyncJobQueue;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-247 / SF-247-04 — l'extraction d'un tour : faits au registre sous user_id + host_id, couverture, isolation. */
@SpringBootTest
@ActiveProfiles("test")
class StateExtractJobIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private StateExtractJobHandler handler;
    @Autowired private StateExtractEnqueuer enqueuer;
    @Autowired private JarvisStateRegistry registry;
    @Autowired private AtelierMessageRepository messages;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private UUID alice;
    private UUID bob;
    private UUID host;
    private Workspace subject;

    @BeforeEach
    void setUp() {
        alice = user();
        bob = user();
        host = hosts.save(RunnerHost.builder().userId(alice).name("CAGIP").rootName("dev").os("linux")
                .shell("posix").elevated(false).build()).getId();
        subject = workspaces.save(Workspace.builder().userId(alice).name("SSO").hostId(host)
                .createdAt(OffsetDateTime.now()).build());
    }

    private UUID user() {
        return users.save(User.builder().email("extract-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build()).getId();
    }

    private static ObjectNode block(String tool, String command, String output, boolean error) {
        ObjectNode b = MAPPER.createObjectNode();
        b.put("tool", tool);
        b.put("command", command);
        b.put("toolUseId", "toolu_" + UUID.randomUUID());
        b.put("output", output);
        b.put("hasOutput", true);
        b.put("error", error);
        return b;
    }

    private AtelierMessage turn(UUID userId, UUID workspaceId) {
        String jira = "curl -s https://jira.ex/rest/api/2/issue/PROJ-7";
        String mr = "glab mr view 3 -F json";
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode blocks = root.putArray("blocks");
        blocks.add(block("bash", jira, "$ " + jira + "\n{\"key\":\"PROJ-7\",\"fields\":{\"status\":{\"name\":\"En revue\"}}}"
                + "\n[code de sortie: 0]", false));
        blocks.add(block("bash", mr, "$ " + mr + "\nerror: 401\n[code de sortie: 1]", false));
        blocks.add(block("bash", "kubectl get deploy -n prod", "$ kubectl get deploy -n prod\nNAME READY\napi 2/2"
                + "\n[code de sortie: 0]", false));
        blocks.add(block("read_file", "src/A.java", "class A {}", false));
        return messages.save(AtelierMessage.builder().workspaceId(workspaceId).userId(userId).role("ASSISTANT")
                .content("ok").terminalJson(root.toString()).createdAt(OffsetDateTime.now()).build());
    }

    private AsyncJobQueue.Job job(UUID userId, UUID messageId, UUID workspaceId) {
        return new AsyncJobQueue.Job(UUID.randomUUID(), userId, StateExtractJobHandler.KIND, 100,
                StateExtractJobHandler.payload(messageId, workspaceId), null, 1, 3, Instant.now());
    }

    @Test
    @DisplayName("un tour : le ticket lu entre au registre (TOUR, HAUTE) ; échec et texte inconnu ignorés ; couverture")
    void extractsTurn() {
        AtelierMessage m = turn(alice, subject.getId());
        AsyncJobHandler.Outcome out = handler.handle(job(alice, m.getId(), subject.getId()));
        assertThat(out.kind()).isEqualTo(AsyncJobHandler.Outcome.Kind.DONE);
        JarvisStateRegistry.Fact f = registry.byKey(alice, host, "JIRA:PROJ-7", 5).get(0);
        assertThat(f.value()).isEqualTo("En revue");
        assertThat(f.source()).isEqualTo("TOUR");
        assertThat(f.confidence()).isEqualTo("HAUTE");
        assertThat(f.workspaceId()).isEqualTo(subject.getId());
        assertThat(registry.recent(alice, host, 20)).hasSize(1);
        Integer calls = jdbc.queryForObject("SELECT sum(calls) FROM jarvis_state_coverage WHERE user_id = ?",
                Integer.class, alice);
        Integer covered = jdbc.queryForObject("SELECT sum(covered) FROM jarvis_state_coverage WHERE user_id = ?",
                Integer.class, alice);
        assertThat(calls).isEqualTo(3);
        assertThat(covered).isEqualTo(1);
    }

    @Test
    @DisplayName("isolation : le travail d'un autre compte sur ce tour ne lit rien ; client coupé ⇒ abandon")
    void isolationAndDisabled() {
        AtelierMessage m = turn(alice, subject.getId());
        assertThat(handler.handle(job(bob, m.getId(), subject.getId())).kind())
                .isEqualTo(AsyncJobHandler.Outcome.Kind.DROP);
        assertThat(registry.recent(alice, host, 20)).isEmpty();
        registry.setEnabled(alice, host, false);
        assertThat(handler.handle(job(alice, m.getId(), subject.getId())).reason()).isEqualTo("disabled");
        assertThat(registry.recent(alice, host, 20)).isEmpty();
    }

    @Test
    @DisplayName("SF-247-05 — git status sans -C : la clé porte le nom du sujet")
    void gitKeyCarriesSubject() {
        ObjectNode root = MAPPER.createObjectNode();
        root.putArray("blocks").add(block("bash", "git status", "$ git status\nOn branch main\nYour branch is up to "
                + "date with 'origin/main'.\n\nnothing to commit, working tree clean\n[code de sortie: 0]", false));
        AtelierMessage m = messages.save(AtelierMessage.builder().workspaceId(subject.getId()).userId(alice)
                .role("ASSISTANT").content("ok").terminalJson(root.toString()).createdAt(OffsetDateTime.now()).build());
        handler.handle(job(alice, m.getId(), subject.getId()));
        assertThat(registry.byKey(alice, host, "GIT:SSO@main", 5)).extracting(JarvisStateRegistry.Fact::value)
                .contains("à jour avec origin/main", "propre");
    }

    @Test
    @DisplayName("Z1 — la remise ne bloque pas le fil appelant et finit en file durable")
    void handoffIsNonBlocking() throws Exception {
        AtelierMessage m = turn(alice, subject.getId());
        long t0 = System.nanoTime();
        enqueuer.afterTurn(alice, subject.getId(), m.getId());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).isLessThan(50);
        long until = System.currentTimeMillis() + 5_000;
        Integer n = 0;
        while (System.currentTimeMillis() < until) {
            n = jdbc.queryForObject("SELECT count(*) FROM async_jobs WHERE user_id = ? AND kind = 'STATE_EXTRACT'",
                    Integer.class, alice);
            if (n > 0) {
                break;
            }
            Thread.sleep(20);
        }
        assertThat(n).isEqualTo(1);
    }
}
