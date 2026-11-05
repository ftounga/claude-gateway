package fr.claudegateway.jarvis.state.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.actions.InterlocuteurService;
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

/** F-247 / SF-247-12 — lire une capture : un appel, la passerelle date et nomme, l'image s'efface, isolation, bornes. */
@SpringBootTest
@ActiveProfiles("test")
class CaptureReadJobIntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    @Autowired private WorkspaceRepository workspaces;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private UserRepository users;
    @Autowired private JarvisStateRegistry registry;
    @Autowired private JarvisStateProperties properties;
    @Autowired private JarvisCaptureStore store;
    @Autowired private CaptureSpeakerResolver speakers;
    @Autowired private JarvisStateSpend spend;
    @Autowired private JarvisFastBrainService fastBrain;
    @Autowired private InterlocuteurService interlocutors;
    @Autowired private AsyncJobQueue queue;
    @Autowired private JarvisUsageService usage;
    @Autowired private JdbcTemplate jdbc;

    private UUID alice;
    private UUID bob;
    private UUID host;
    private Workspace subject;
    private final List<AgentTurnRequest> seen = new ArrayList<>();
    private final AtomicReference<String> answer = new AtomicReference<>();

    private final AiAgentProvider stub = request -> {
        seen.add(request);
        return new AgentTurn(answer.get(), List.of(), true, 1_200, 200);
    };

    @BeforeEach
    void setUp() {
        alice = user();
        bob = user();
        host = hosts.save(RunnerHost.builder().userId(alice).name("CAGIP").rootName("dev").os("linux")
                .shell("posix").elevated(false).build()).getId();
        subject = workspaces.save(Workspace.builder().userId(alice).name("SSO").hostId(host)
                .createdAt(OffsetDateTime.now()).build());
        interlocutors.resolve(alice, host, "Rémi Dupont");
        answer.set("{\"conversation\":true,\"plateforme\":\"teams\",\"titre\":\"Migration SSO\",\"messages\":["
                + "{\"interlocuteur\":\"Dupont, Rémi\",\"horodatage_visible\":\"Hier 14:32\","
                + "\"texte\":\"Le ticket PROJ-123 passe en revue demain.\"},"
                + "{\"interlocuteur\":\"Dupont, Rémi\",\"horodatage_visible\":\"\",\"texte\":\"Et la MR suit.\"}]}");
        seen.clear();
    }

    private UUID user() {
        return users.save(User.builder().email("capture-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build()).getId();
    }

    private CaptureReadJobHandler handler(int dailyMax) {
        return new CaptureReadJobHandler(workspaces, registry, properties, store, speakers, spend, stub, fastBrain,
                dailyMax);
    }

    private AsyncJobQueue.Job job(UUID userId, Workspace w, UUID depositId) {
        return new AsyncJobQueue.Job(UUID.randomUUID(), userId, CaptureReadJobHandler.KIND, 100,
                CaptureReadJobHandler.payload(depositId, w.getId(), host, Instant.parse("2026-11-05T09:15:00Z"),
                        "image/png"), PNG, 1, 2, Instant.now());
    }

    @Test
    @DisplayName("Une capture lue : un appel avec le bloc image, lignes datées et nommées par la passerelle, coût compté")
    void readsOnce() {
        UUID deposit = UUID.randomUUID();
        AsyncJobHandler.Outcome out = handler(60).handle(job(alice, subject, deposit));

        assertThat(out.kind()).isEqualTo(AsyncJobHandler.Outcome.Kind.DONE);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).system()).isEqualTo(CaptureReadPrompt.SYSTEM);
        assertThat(seen.get(0).messages().get(0).content().get(0)).isInstanceOf(AgentContentBlock.Image.class);
        List<JarvisCaptureStore.LineView> lines = store.recentLines(alice, host, 20);
        assertThat(lines).hasSize(2);
        JarvisCaptureStore.LineView first = lines.stream().filter(l -> l.text().contains("PROJ-123")).findFirst()
                .orElseThrow();
        assertThat(first.speaker()).isEqualTo("Rémi Dupont");
        assertThat(first.speakerKnown()).isTrue();
        assertThat(first.approx()).isFalse();
        assertThat(first.saidAt().toInstant()).isEqualTo(Instant.parse("2026-11-04T13:32:00Z"));
        JarvisCaptureStore.LineView second = lines.stream().filter(l -> l.text().contains("MR")).findFirst()
                .orElseThrow();
        assertThat(second.approx()).isTrue();
        assertThat(second.saidAt()).isEqualTo(first.saidAt());
        assertThat(first.platform()).isEqualTo("TEAMS");
        assertThat(store.count(alice, host)).isEqualTo(1);

        // Rejoué : rien en double, aucun nouvel appel.
        handler(60).handle(job(alice, subject, deposit));
        assertThat(seen).hasSize(1);
        assertThat(store.recentLines(alice, host, 20)).hasSize(2);
    }

    @Test
    @DisplayName("Isolation : le sujet d'un autre compte est introuvable ; ses lignes ne se lisent pas sous un autre compte")
    void isolation() {
        assertThat(handler(60).handle(job(bob, subject, UUID.randomUUID())).reason()).isEqualTo("no_client");
        assertThat(seen).isEmpty();
        handler(60).handle(job(alice, subject, UUID.randomUUID()));
        assertThat(store.recentLines(bob, host, 20)).isEmpty();
    }

    @Test
    @DisplayName("Pas une conversation, ou sortie non conforme : rien n'est gardé, le coût est compté")
    void nothingKept() {
        answer.set("{\"conversation\":false}");
        handler(60).handle(job(alice, subject, UUID.randomUUID()));
        answer.set("Je vois une conversation entre Rémi et vous.");
        handler(60).handle(job(alice, subject, UUID.randomUUID()));
        assertThat(store.recentLines(alice, host, 20)).isEmpty();
        assertThat(store.count(alice, host)).isZero();
        assertThat(usage.monthCaptureCostUsd(alice).signum()).isPositive();
    }

    @Test
    @DisplayName("Client coupé ⇒ abandon ; plafond du jour ⇒ abandon ; couper efface les transcriptions")
    void bounds() {
        handler(60).handle(job(alice, subject, UUID.randomUUID()));
        assertThat(handler(1).handle(job(alice, subject, UUID.randomUUID())).reason()).isEqualTo("daily_cap");
        registry.setEnabled(alice, host, false);
        assertThat(store.recentLines(alice, host, 20)).isEmpty();
        assertThat(handler(60).handle(job(alice, subject, UUID.randomUUID())).reason()).isEqualTo("disabled");
        registry.setEnabled(alice, host, true);
    }

    @Test
    @DisplayName("L'image vit dans la file le temps de la lecture, puis est effacée")
    void imageErased() {
        UUID id = queue.enqueue(new AsyncJobQueue.Request(alice, "CAPTURE_TEST", 100, "{}", PNG, 2)).orElseThrow();
        List<AsyncJobQueue.Job> taken = queue.claim("CAPTURE_TEST", 1, "me");
        assertThat(taken.get(0).payloadBytes()).isEqualTo(PNG);
        queue.complete(id, "me", 5, java.math.BigDecimal.ZERO);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM async_jobs WHERE id = ? AND payload_bytes IS NULL",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("Porte Z1b : la remise d'un dépôt ne bloque jamais, même file bloquée ; seules les images sont prises")
    void handoffNeverBlocks() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AsyncJobQueue blocked = new AsyncJobQueue(jdbc, null, java.time.Clock.systemUTC(), null) {
            @Override
            public java.util.Optional<UUID> enqueue(Request r) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return java.util.Optional.empty();
            }
        };
        CaptureReadEnqueuer enqueuer = new CaptureReadEnqueuer(blocked, properties);
        long t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            enqueuer.received(subject, UUID.randomUUID(), "image/png", PNG, Instant.now());
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        release.countDown();
        assertThat(ms).isLessThan(200);
        assertThat(enqueuer.rejected()).isPositive();
        assertThat(CaptureReadEnqueuer.mediaType("image/png", "texte".getBytes())).isNull();
        assertThat(CaptureReadEnqueuer.mediaType(null, PNG)).isEqualTo("image/png");
    }
}
