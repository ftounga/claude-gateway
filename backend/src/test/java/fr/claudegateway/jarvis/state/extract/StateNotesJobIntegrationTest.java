package fr.claudegateway.jarvis.state.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.fastbrain.JarvisFastBrainService;
import fr.claudegateway.jarvis.state.JarvisStateProperties;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateSpend;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.shared.jobs.AsyncJobHandler;
import fr.claudegateway.shared.jobs.AsyncJobQueue;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-247 / SF-247-14 — constats rédigés : sorties non couvertes, citation vérifiée, NOTE/MOYENNE, isolation, secrets. */
@SpringBootTest
@ActiveProfiles("test")
class StateNotesJobIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private AtelierMessageRepository messages;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private UserRepository users;
    @Autowired private JarvisStateRegistry registry;
    @Autowired private StateExtractJobHandler extract;
    @Autowired private JarvisStateProperties properties;
    @Autowired private JarvisStateSpend spend;
    @Autowired private JarvisFastBrainService fastBrain;
    @Autowired private AsyncJobQueue queue;
    @Autowired private JarvisUsageService usage;
    @Autowired private JdbcTemplate jdbc;

    private UUID alice;
    private UUID bob;
    private UUID host;
    private Workspace subject;
    private final List<AgentTurnRequest> seen = new ArrayList<>();
    private final AtomicReference<String> answer = new AtomicReference<>();
    private final AiAgentProvider stub = r -> {
        seen.add(r);
        return new AgentTurn(answer.get(), List.of(), true, 2_000, 120);
    };

    @BeforeEach
    void setUp() {
        alice = user();
        bob = user();
        host = hosts.save(RunnerHost.builder().userId(alice).name("CAGIP").rootName("dev").os("linux")
                .shell("posix").elevated(false).build()).getId();
        subject = workspaces.save(Workspace.builder().userId(alice).name("SSO").hostId(host)
                .createdAt(OffsetDateTime.now()).build());
        answer.set("{\"faits\":[{\"sortie\":1,\"objet\":\"api\",\"famille\":\"DEPLOYMENT\",\"attribut\":\"replicas\","
                + "\"valeur\":\"2/2\",\"citation\":\"api 2/2\"},{\"sortie\":1,\"objet\":\"api\",\"famille\":"
                + "\"DEPLOYMENT\",\"attribut\":\"health\",\"valeur\":\"sain\",\"citation\":\"api est sain\"}]}");
        seen.clear();
    }

    private UUID user() {
        return users.save(User.builder().email("notes-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build()).getId();
    }

    private AtelierMessage turn(UUID userId) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode b = root.putArray("blocks").addObject();
        b.put("tool", "bash");
        b.put("command", "kubectl get deploy -n prod");
        b.put("toolUseId", "toolu_" + UUID.randomUUID());
        b.put("output", "$ kubectl get deploy -n prod\nNAME READY\napi 2/2\n[code de sortie: 0]");
        b.put("error", false);
        return messages.save(AtelierMessage.builder().workspaceId(subject.getId()).userId(userId).role("ASSISTANT")
                .content("ok").terminalJson(root.toString()).createdAt(OffsetDateTime.now()).build());
    }

    private StateNotesJobHandler handler() {
        return new StateNotesJobHandler(messages, workspaces, registry, extract, properties, spend, stub, fastBrain,
                queue, jdbc, 200);
    }

    private AsyncJobQueue.Job job(UUID userId, UUID messageId) {
        return new AsyncJobQueue.Job(UUID.randomUUID(), userId, StateNotesJobHandler.KIND, 100,
                StateExtractJobHandler.payload(messageId, subject.getId()), null, 1, 3, Instant.now());
    }

    @Test
    @DisplayName("Une sortie non couverte : un appel, seul le fait cité mot pour mot entre (NOTE, MOYENNE), coût compté")
    void citedFactOnly() {
        AtelierMessage m = turn(alice);
        AsyncJobHandler.Outcome out = handler().handle(job(alice, m.getId()));

        assertThat(out.kind()).isEqualTo(AsyncJobHandler.Outcome.Kind.DONE);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).system()).isEqualTo(StateNotesGuard.SYSTEM);
        List<JarvisStateRegistry.Fact> facts = registry.recent(alice, host, 20);
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).objectKey()).isEqualTo("NOTE:api");
        assertThat(facts.get(0).value()).isEqualTo("2/2");
        assertThat(facts.get(0).source()).isEqualTo("NOTE");
        assertThat(facts.get(0).confidence()).isEqualTo("MOYENNE");
        assertThat(usage.monthNotesCostUsd(alice).signum()).isPositive();
    }

    @Test
    @DisplayName("L'extraction d'un tour met ses sorties non couvertes aux constats")
    void extractionEnqueuesNotes() {
        AtelierMessage m = turn(alice);
        extract.handle(new AsyncJobQueue.Job(UUID.randomUUID(), alice, StateExtractJobHandler.KIND, 100,
                StateExtractJobHandler.payload(m.getId(), subject.getId()), null, 1, 3, Instant.now()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM async_jobs WHERE user_id = ? AND kind = 'STATE_NOTES'",
                Integer.class, alice)).isEqualTo(1);
    }

    @Test
    @DisplayName("Isolation et coupure : autre compte ⇒ abandon sans appel ; constats coupés ⇒ abandon")
    void isolationAndDisabled() {
        AtelierMessage m = turn(alice);
        assertThat(handler().handle(job(bob, m.getId())).reason()).isEqualTo("no_client");
        var s = registry.settingsOf(alice, host);
        s.setNotesEnabled(false);
        registry.save(s);
        assertThat(handler().handle(job(alice, m.getId())).reason()).isEqualTo("disabled");
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("Filtre « aucun secret » à l'enregistrement : quelle que soit la source, rien n'entre")
    void secretsNeverRecorded() {
        long before = JarvisStateRegistry.secretsRejected();
        OffsetDateTime now = OffsetDateTime.now();
        int written = registry.record(alice, List.of(
                new JarvisStateRegistry.Observation(host, null, "OTHER", "API:svc/conf", "value",
                        "token=abcdef123456", now, "RELEVE", null, "MOYENNE"),
                new JarvisStateRegistry.Observation(host, null, "OTHER", "API:svc/x?api_key=zz9988776655", "status",
                        "OK", now, "VEILLE", null, "MOYENNE"),
                new JarvisStateRegistry.Observation(host, null, "OTHER", "API:svc/ok", "status", "OK", now, "NOTE",
                        null, "MOYENNE")));
        assertThat(written).isEqualTo(1);
        assertThat(JarvisStateRegistry.secretsRejected() - before).isEqualTo(2);
    }
}
