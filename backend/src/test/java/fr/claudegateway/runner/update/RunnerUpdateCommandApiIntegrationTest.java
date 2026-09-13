package fr.claudegateway.runner.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.channel.RunnerOutbound;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-111 / SF-111-04 — « Mettre à jour », de bout en bout : autorisation (propriétaire, ADMIN, autrui),
 * préconditions, journal, remise de la trame sur le canal du poste, suivi des statuts et du retour.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerUpdateCommandApiIntegrationTest {

    private static final String SERVED = RunnerUpdateArtifactsTest.ID;
    private static final String OLD = "1.0.0-202609130000-aaa1111";

    @DynamicPropertySource
    static void servedVersion(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("runner-update-command-test");
        RunnerUpdateArtifactsTest.deposit(dir, SERVED, true, null);
        registry.add("app.runner.update-dir", dir::toString);
        registry.add("app.runner.min-version", () -> SERVED);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerUpdateJournalRepository journalRepository;
    @Autowired private RunnerCallDispatcher dispatcher;
    @Autowired private RunnerUpdateService updateService;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    private final List<String> sent = new CopyOnWriteArrayList<>();
    private String ownerToken;
    private String otherToken;
    private String adminToken;
    private RunnerHost host;

    @BeforeEach
    void setUp() {
        journalRepository.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        sent.clear();

        User owner = user("owner-update@example.com", UserRole.USER);
        subscribe(owner);
        ownerToken = jwtService.generateToken(owner);
        User other = user("other-update@example.com", UserRole.USER);
        subscribe(other);
        otherToken = jwtService.generateToken(other);
        adminToken = jwtService.generateToken(user("admin-update@example.com", UserRole.ADMIN));

        host = hostRepository.save(RunnerHost.builder().userId(owner.getId()).name("Poste CAGIP")
                .runnerVersion(OLD).runnerContract(2).runnerLauncher(true).runnerJava(21)
                .runnerCapabilities("files,bash").build());
        // Le canal du poste vit sur ce pod (long-polling ou WebSocket : la même interface).
        dispatcher.attachChannel(new RunnerIdentity(UUID.randomUUID(), owner.getId(), host.getId()),
                new RunnerOutbound() {
                    @Override
                    public void send(String frame) {
                        sent.add(frame);
                    }

                    @Override
                    public boolean isOpen() {
                        return true;
                    }

                    @Override
                    public void close() {
                    }
                });
    }

    @Test
    void theOwnerUpdatesAndTheRunnerReceivesTheCommand() throws Exception {
        mockMvc.perform(post("/api/runner-hosts/" + host.getId() + "/runner-update").contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"force\":false}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("REQUESTED"))
                .andExpect(jsonPath("$.fromVersion").value(OLD))
                .andExpect(jsonPath("$.toVersion").value(SERVED))
                .andExpect(jsonPath("$.active").value(true));

        assertThat(sent).hasSize(1);
        JsonNode frame = objectMapper.readTree(sent.get(0));
        assertThat(frame.path("type").asText()).isEqualTo("update");
        assertThat(frame.path("version").asText()).isEqualTo(SERVED);
        assertThat(frame.path("sha256").asText()).hasSize(64);
        RunnerUpdateJournalEntry entry = journalRepository.findAll().get(0);
        assertThat(frame.path("updateId").asText()).isEqualTo(entry.getId().toString());
        assertThat(entry.getRequestedBy()).isEqualTo(host.getUserId());

        // Une seconde demande sans « Forcer » est refusée ; avec, la commande repart.
        mockMvc.perform(post("/api/runner-hosts/" + host.getId() + "/runner-update").contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("runner_update_in_progress"));
        mockMvc.perform(post("/api/runner-hosts/" + host.getId() + "/runner-update").contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"force\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.forced").value(true));
        assertThat(objectMapper.readTree(sent.get(1)).path("force").asBoolean()).isTrue();

        // Le runner dit où il en est, puis revient dans la version visée.
        RunnerIdentity session = new RunnerIdentity(UUID.randomUUID(), host.getUserId(), host.getId());
        dispatcher.onFrame(session, "update_status", objectMapper.readTree("{\"type\":\"update_status\","
                + "\"updateId\":\"" + entry.getId() + "\",\"state\":\"waiting\",\"busy\":[\"commande\"]}"));
        assertThat(journalRepository.findById(entry.getId()).orElseThrow().getDetail()).isEqualTo("commande");
        dispatcher.onFrame(session, "ready", objectMapper.readTree("{\"type\":\"ready\",\"runnerVersion\":\""
                + SERVED + "\",\"contract\":2,\"launcher\":true,\"javaVersion\":21}"));

        mockMvc.perform(get("/api/runner-hosts/" + host.getId() + "/runner-update/journal").contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].active").value(false));
    }

    @Test
    void theLauncherRollbackReportClosesTheUpdateEvenIfItLookedSuccessful() throws Exception {
        // F-111 / SF-111-05 : la nouvelle version s'est connectée (réussie), puis a planté 3 fois ; le lanceur
        // est revenu à l'ancienne, qui le dit dans sa trame ready.
        mockMvc.perform(post("/api/runner-hosts/" + host.getId() + "/runner-update").contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
        RunnerIdentity session = new RunnerIdentity(UUID.randomUUID(), host.getUserId(), host.getId());
        dispatcher.onFrame(session, "ready", objectMapper.readTree("{\"type\":\"ready\",\"runnerVersion\":\""
                + SERVED + "\",\"contract\":2,\"launcher\":true}"));
        assertThat(journalRepository.findAll().get(0).getState()).isEqualTo(RunnerUpdateJournalEntry.State.SUCCEEDED);

        dispatcher.onFrame(session, "ready", objectMapper.readTree("{\"type\":\"ready\",\"runnerVersion\":\"" + OLD
                + "\",\"contract\":2,\"launcher\":true,\"lastUpdate\":{\"from\":\"" + OLD + "\",\"to\":\"" + SERVED
                + "\",\"result\":\"rolled_back\",\"reason\":\"3 plantages de la version (dernier code 1)\"}}"));

        mockMvc.perform(get("/api/runner-hosts/" + host.getId() + "/runner-update/journal").contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("ROLLED_BACK"))
                .andExpect(jsonPath("$[0].detail").value("3 plantages de la version (dernier code 1)"));

        // Un rapport venu d'une AUTRE session ne touche pas ce poste.
        RunnerIdentity intruder = new RunnerIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        dispatcher.onFrame(intruder, "ready", objectMapper.readTree("{\"type\":\"ready\",\"runnerVersion\":\"" + OLD
                + "\",\"lastUpdate\":{\"to\":\"" + SERVED + "\",\"result\":\"rolled_back\",\"reason\":\"x\"}}"));
        assertThat(journalRepository.findAll().get(0).getDetail()).isEqualTo("3 plantages de la version (dernier code 1)");
    }

    @Test
    void anAdminMayUpdateSomeoneElsesMachine() throws Exception {
        mockMvc.perform(post("/api/runner-hosts/" + host.getId() + "/runner-update").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isAccepted());
        RunnerUpdateJournalEntry entry = journalRepository.findAll().get(0);
        assertThat(entry.getUserId()).as("la ligne appartient au propriétaire").isEqualTo(host.getUserId());
        assertThat(entry.getRequestedBy()).as("et dit qui a cliqué").isNotEqualTo(host.getUserId());
    }

    @Test
    void anotherUserSeesNothing() throws Exception {
        mockMvc.perform(post("/api/runner-hosts/" + host.getId() + "/runner-update").contextPath("/api")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/runner-hosts/" + host.getId() + "/runner-update/journal").contextPath("/api")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        assertThat(sent).isEmpty();
        assertThat(journalRepository.findAll()).isEmpty();
    }

    @Test
    void anUpToDateOrLauncherlessRunnerIsNotUpdated() throws Exception {
        host.setRunnerVersion(SERVED);
        hostRepository.save(host);
        mockMvc.perform(post("/api/runner-hosts/" + host.getId() + "/runner-update").contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("up_to_date"));

        host.setRunnerVersion(OLD);
        host.setRunnerLauncher(null);
        host.setRunnerContract(null);
        hostRepository.save(host);
        mockMvc.perform(post("/api/runner-hosts/" + host.getId() + "/runner-update").contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("no_launcher"));
        assertThat(sent).isEmpty();
    }

    @Test
    void anUnreachableRunnerIsJournaledAsFailed() throws Exception {
        RunnerHost offline = hostRepository.save(RunnerHost.builder().userId(host.getUserId()).name("Hors ligne")
                .runnerVersion(OLD).runnerContract(2).runnerLauncher(true).runnerJava(21).build());

        mockMvc.perform(post("/api/runner-hosts/" + offline.getId() + "/runner-update").contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("runner_unavailable"));
        assertThat(journalRepository.findTop20ByHostIdOrderByRequestedAtDesc(offline.getId()))
                .extracting(RunnerUpdateJournalEntry::getState)
                .containsExactly(RunnerUpdateJournalEntry.State.FAILED);
    }

    @Test
    void aStatusFromAnotherSessionChangesNothing() throws Exception {
        mockMvc.perform(post("/api/runner-hosts/" + host.getId() + "/runner-update").contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
        RunnerUpdateJournalEntry entry = journalRepository.findAll().get(0);

        RunnerIdentity intruder = new RunnerIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        dispatcher.onFrame(intruder, "update_status", objectMapper.readTree("{\"type\":\"update_status\","
                + "\"updateId\":\"" + entry.getId() + "\",\"state\":\"failed\",\"reason\":\"x\"}"));

        assertThat(journalRepository.findById(entry.getId()).orElseThrow().getState())
                .isEqualTo(RunnerUpdateJournalEntry.State.REQUESTED);

        // Le runner du poste revient, mais dans son ANCIENNE version : la mise à jour a échoué.
        RunnerIdentity session = new RunnerIdentity(UUID.randomUUID(), host.getUserId(), host.getId());
        dispatcher.onFrame(session, "ready", objectMapper.readTree("{\"type\":\"ready\",\"runnerVersion\":\""
                + OLD + "\",\"contract\":2,\"launcher\":true}"));
        RunnerUpdateJournalEntry failed = journalRepository.findById(entry.getId()).orElseThrow();
        assertThat(failed.getState()).isEqualTo(RunnerUpdateJournalEntry.State.FAILED);
        assertThat(failed.getDetail()).contains(OLD);
        assertThat(failed.getFinishedAt()).isNotNull();
    }

    private User user(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private void subscribe(User user) {
        Subscription subscription = subscriptionRepository.findByUserId(user.getId())
                .orElseGet(() -> Subscription.builder().userId(user.getId()).build());
        subscription.setPlanCode(PlanCode.GOLD);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setAtelierOptionStatus(null);
        subscription.setTeamsOptionStatus(null);
        subscriptionRepository.save(subscription);
    }
}
