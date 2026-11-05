package fr.claudegateway.jarvis.state.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.veille.JarvisWatch;
import fr.claudegateway.atelier.veille.JarvisWatchItems;
import fr.claudegateway.atelier.veille.JarvisWatchProbeRunner;
import fr.claudegateway.atelier.veille.JarvisWatchRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateVocabulary;
import fr.claudegateway.jarvis.state.capture.CaptureReadJobHandler;
import fr.claudegateway.jarvis.state.capture.JarvisCaptureStore;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.shared.jobs.AsyncJobQueue;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-247 / SF-247-16 — « Synchroniser » : historique (date d'origine, jamais d'écrasement par plus ancien, secrets
 * filtrés, idempotent, 0 € et aucun constat), captures déjà déposées (poste éteint ⇒ attente ; priorité basse, date du
 * dépôt), relevé du jour, cession aux tours, annulation, chaîne non dédoublée, isolation et API.
 */
@SpringBootTest(properties = "app.jobs.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisStateSyncIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    @MockitoBean private RunnerLiveness liveness;
    @MockitoBean private RunnerToolGateway runner;
    @MockitoBean private JarvisWatchProbeRunner probes;

    @Autowired private JarvisStateSyncService syncs;
    @Autowired private JarvisStateSyncStore store;
    @Autowired private StateSyncJobHandler handler;
    @Autowired private JarvisStateRegistry registry;
    @Autowired private JarvisCaptureStore captures;
    @Autowired private AtelierMessageRepository messages;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private JarvisWatchRepository watches;
    @Autowired private UserRepository users;
    @Autowired private LiveTurnRegistry liveTurns;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    private User alice;
    private User bob;
    private UUID host;
    private Workspace subject;

    @BeforeEach
    void setUp() {
        alice = user();
        bob = user();
        host = hosts.save(RunnerHost.builder().userId(alice.getId()).name("CAGIP").rootName("dev").os("linux")
                .shell("posix").elevated(false).runnerCapabilities("files,bash,low_priority").build()).getId();
        subject = workspaces.save(Workspace.builder().userId(alice.getId()).name("SSO").hostId(host)
                .executionTarget(WorkspaceExecutionTarget.RUNNER).createdAt(OffsetDateTime.now()).build());
        when(liveness.isAlive(any(), any())).thenReturn(false);
    }

    private User user() {
        return users.save(User.builder().email("sync-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build());
    }

    private static ObjectNode block(String toolUseId, String command, String output) {
        ObjectNode b = MAPPER.createObjectNode();
        b.put("tool", "bash");
        b.put("command", command);
        b.put("toolUseId", toolUseId);
        b.put("output", "$ " + command + "\n" + output + "\n[code de sortie: 0]");
        b.put("hasOutput", true);
        b.put("error", false);
        return b;
    }

    /** Une réponse ancienne : une lecture Jira à l'heure {@code toolAt} (journal du runner), réponse à {@code at}. */
    private AtelierMessage oldTurn(String issue, String status, OffsetDateTime toolAt, OffsetDateTime at) {
        String toolUseId = "toolu_" + UUID.randomUUID();
        String cmd = "curl -s https://jira.ex/rest/api/2/issue/" + issue;
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode blocks = root.putArray("blocks");
        blocks.add(block(toolUseId, cmd, "{\"key\":\"" + issue + "\",\"fields\":{\"status\":{\"name\":\"" + status
                + "\"}}}"));
        AtelierMessage m = messages.save(AtelierMessage.builder().workspaceId(subject.getId()).userId(alice.getId())
                .role("ASSISTANT").content("ok").terminalJson(root.toString()).createdAt(at).build());
        jdbc.update("UPDATE atelier_messages SET created_at = ? WHERE id = ?", Timestamp.from(at.toInstant()), m.getId());
        jdbc.update("INSERT INTO runner_audit (id, user_id, workspace_id, call_id, tool, outcome, created_at) "
                + "VALUES (?,?,?,?,?,?,?)", UUID.randomUUID(), alice.getId(), subject.getId(), toolUseId, "bash", "ok",
                Timestamp.from(toolAt.toInstant()));
        return m;
    }

    private UUID deposit(String path, OffsetDateTime at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO atelier_deposited_files (id, user_id, workspace_id, path, size_bytes, created_at) "
                + "VALUES (?,?,?,?,?,?)", id, alice.getId(), subject.getId(), path, PNG.length,
                Timestamp.from(at.toInstant()));
        return id;
    }

    /** Fait tourner les tranches du passage (comme la file), jusqu'à {@code max} ou la fin. */
    private SyncRow drive(UUID userId, UUID syncId, int max) {
        for (int i = 0; i < max; i++) {
            SyncRow s = store.find(userId, syncId).orElseThrow();
            if (!s.active()) {
                return s;
            }
            handler.handle(job(userId, syncId, s.getStep()));
        }
        return store.find(userId, syncId).orElseThrow();
    }

    private static AsyncJobQueue.Job job(UUID userId, UUID syncId, int step) {
        return new AsyncJobQueue.Job(UUID.randomUUID(), userId, StateSyncJobHandler.KIND, StateSyncJobHandler.PRIORITY,
                StateSyncJobHandler.payload(syncId, step), null, 1, 3, Instant.now());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String token) {
        return r.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    @Test
    @DisplayName("Historique : date d'origine, plus récent jamais écrasé, idempotent, 0 € et aucun constat")
    void historyKeepsOriginAndWaitsForHost() {
        OffsetDateTime tenDays = OffsetDateTime.now().minusDays(10).truncatedTo(ChronoUnit.SECONDS);
        oldTurn("PROJ-7", "En revue", tenDays, tenDays.plusMinutes(1));
        oldTurn("PROJ-8", "Ouvert", tenDays.plusHours(1), tenDays.plusHours(1).plusMinutes(1));
        // Un fait plus récent est déjà au registre : le rattrapage ne doit pas le remplacer.
        registry.record(alice.getId(), List.of(new JarvisStateRegistry.Observation(host, subject.getId(), "TICKET",
                "JIRA:PROJ-8", "status", "Terminé", OffsetDateTime.now(), JarvisStateVocabulary.SOURCE_TOUR, "x",
                JarvisStateVocabulary.HAUTE)));

        JarvisStateSyncService.Started started = syncs.start(alice.getId(), host);
        assertThat(started.created()).isTrue();
        // Relancer pendant qu'il est actif rend le même passage.
        assertThat(syncs.start(alice.getId(), host).sync().id()).isEqualTo(started.sync().id());

        SyncRow s = drive(alice.getId(), started.sync().id(), 10);
        assertThat(s.getP1Status()).isEqualTo(SyncRow.P_DONE);
        assertThat(s.getP1Total()).isEqualTo(2);
        assertThat(s.getP1Done()).isEqualTo(2);
        assertThat(s.getP1Facts()).isEqualTo(1);
        JarvisStateRegistry.Fact f7 = registry.byKey(alice.getId(), host, "JIRA:PROJ-7", 5).get(0);
        assertThat(f7.value()).isEqualTo("En revue");
        assertThat(f7.observedAt().toInstant()).isEqualTo(tenDays.toInstant());
        assertThat(registry.byKey(alice.getId(), host, "JIRA:PROJ-8", 5).get(0).value()).isEqualTo("Terminé");
        // Aucune capture à relire, relevé non mis en place : le passage se termine sans le poste.
        assertThat(s.getP2Status()).isEqualTo(SyncRow.P_DONE);
        assertThat(s.getP3Status()).isEqualTo(SyncRow.P_SKIPPED);
        assertThat(s.getStatus()).isEqualTo(SyncRow.DONE);
        // Relancer : rien n'est réécrit (mêmes observations, upsert idempotent).
        SyncRow again = drive(alice.getId(), syncs.start(alice.getId(), host).sync().id(), 10);
        assertThat(again.getP1Done()).isEqualTo(2);
        assertThat(again.getP1Facts()).isZero();
        assertThat(registry.recent(alice.getId(), host, 20)).hasSize(2);
        Integer notes = jdbc.queryForObject("SELECT count(*) FROM async_jobs WHERE user_id = ? AND kind = 'STATE_NOTES'",
                Integer.class, alice.getId());
        assertThat(notes).isZero();
        Integer coverage = jdbc.queryForObject("SELECT count(*) FROM jarvis_state_coverage WHERE user_id = ?",
                Integer.class, alice.getId());
        assertThat(coverage).isZero();
    }

    @Test
    @DisplayName("Captures déposées : poste éteint ⇒ en attente ; allumé ⇒ lecture en file, priorité basse, date du "
            + "dépôt d'origine ; déjà lue ⇒ ignorée ; relevé non mis en place ⇒ sauté ; relancer ne double rien")
    void capturesThenRelevé() throws Exception {
        OffsetDateTime depositedAt = OffsetDateTime.now().minusDays(20).truncatedTo(ChronoUnit.SECONDS);
        UUID d1 = deposit(".atelier/entrees/capture-1.png", depositedAt);
        UUID read = deposit(".atelier/entrees/capture-2.png", depositedAt.plusHours(1));
        deposit(".atelier/entrees/notes.txt", depositedAt);
        captures.save(alice.getId(), host, subject.getId(), read, "TEAMS", "déjà", depositedAt, "LUE",
                java.math.BigDecimal.ZERO, List.of());
        when(runner.readFileBytes(any(), anyString(), anyString(), anyLong(), anyInt())).thenReturn(new RunnerCallResult(
                true, Base64.getEncoder().encodeToString(PNG), false, null, 3L, (long) PNG.length, null, null, null,
                false));

        UUID id = syncs.start(alice.getId(), host).sync().id();
        SyncRow s = drive(alice.getId(), id, 5);
        assertThat(s.getP2Status()).isEqualTo(SyncRow.P_WAITING);
        assertThat(s.getNote()).isEqualTo(SyncRow.NOTE_HOST_OFF);
        assertThat(s.getP2Total()).isEqualTo(1);
        verify(runner, never()).readFileBytes(any(), anyString(), anyString(), anyLong(), anyInt());

        when(liveness.isAlive(any(), any())).thenReturn(true);
        s = drive(alice.getId(), id, 3);
        List<String> payloads = jdbc.queryForList("SELECT payload FROM async_jobs WHERE user_id = ? AND kind = ? "
                + "AND priority = ?", String.class, alice.getId(), CaptureReadJobHandler.KIND,
                StateSyncJobHandler.PRIORITY);
        assertThat(payloads).hasSize(1);
        assertThat(payloads.get(0)).contains(d1.toString()).contains(id.toString())
                .contains(depositedAt.toInstant().toString());
        assertThat(s.getP2Done()).isEqualTo(1);
        assertThat(s.getNote()).isEqualTo(SyncRow.NOTE_READING);

        // La lecture se termine (comme le ferait CAPTURE_READ) : la phase 2 finit, le relevé (non mis en place) est sauté.
        jdbc.update("UPDATE async_jobs SET status = 'DONE', cost_usd = 0.004, payload_bytes = NULL WHERE user_id = ? "
                + "AND kind = ?", alice.getId(), CaptureReadJobHandler.KIND);
        captures.save(alice.getId(), host, subject.getId(), d1, "TEAMS", "Migration SSO", depositedAt, "LUE",
                java.math.BigDecimal.ZERO, List.of());
        s = drive(alice.getId(), id, 5);
        assertThat(s.getP2Status()).isEqualTo(SyncRow.P_DONE);
        assertThat(s.getP2Read()).isEqualTo(1);
        assertThat(s.getP2CostUsd()).isEqualByComparingTo("0.004");
        assertThat(s.getP3Status()).isEqualTo(SyncRow.P_SKIPPED);
        assertThat(s.getP3Detail()).isEqualTo("not_setup");
        assertThat(s.getStatus()).isEqualTo(SyncRow.DONE);
        assertThat(s.getFinishedAt()).isNotNull();

        // Relancer : un nouveau passage, mais la capture déjà en file/lue n'est pas renvoyée.
        UUID again = syncs.start(alice.getId(), host).sync().id();
        assertThat(again).isNotEqualTo(id);
        drive(alice.getId(), again, 6);
        Integer reads = jdbc.queryForObject("SELECT count(*) FROM async_jobs WHERE user_id = ? AND kind = ?",
                Integer.class, alice.getId(), CaptureReadJobHandler.KIND);
        assertThat(reads).isEqualTo(1);
    }

    @Test
    @DisplayName("Relevé du jour : chaque sonde passe une fois ; tour en cours ⇒ pause, rien ne part, puis reprise")
    void releveYieldsToTurns() {
        when(liveness.isAlive(any(), any())).thenReturn(true);
        OffsetDateTime now = OffsetDateTime.now();
        watches.save(JarvisWatch.builder().userId(alice.getId()).workspaceId(subject.getId()).name("Tickets")
                .platform("Jira").scope("x").probeCommand("jira issue list --plain").runTimes("08:00")
                .timeZone("Europe/Paris").active(true).proposed(false).origin(JarvisWatch.ORIGIN_AGENT)
                .purpose(JarvisWatch.PURPOSE_STATE).createdAt(now).updatedAt(now).build());
        when(probes.run(any(), any())).thenReturn(new JarvisWatchProbeRunner.Outcome(List.of(
                new JarvisWatchItems.Item("PROJ-1", "SSO", "En revue", "", "", "")), null, null, 5L));

        UUID id = syncs.start(alice.getId(), host).sync().id();
        LiveTurn turn = liveTurns.open(alice.getId(), subject.getId());
        SyncRow s;
        try {
            s = drive(alice.getId(), id, 1);
            assertThat(s.getNote()).isEqualTo(SyncRow.NOTE_TURN);
            assertThat(s.getP1Status()).isEqualTo(SyncRow.P_PAUSED);
        } finally {
            liveTurns.close(turn);
        }
        s = drive(alice.getId(), id, 10);
        assertThat(s.getStatus()).isEqualTo(SyncRow.DONE);
        assertThat(s.getP3Status()).isEqualTo(SyncRow.P_DONE);
        assertThat(s.getP3Total()).isEqualTo(1);
        assertThat(s.getP3Facts()).isGreaterThanOrEqualTo(1);
        assertThat(registry.byKey(alice.getId(), host, "JIRA:PROJ-1", 5)).isNotEmpty();
    }

    @Test
    @DisplayName("Annuler : le passage s'arrête, ses lectures en attente sont abandonnées ; un travail périmé est "
            + "abandonné (chaîne non dédoublée)")
    void cancelAndStale() {
        UUID id = syncs.start(alice.getId(), host).sync().id();
        handler.handle(job(alice.getId(), id, 0));
        // Rejouer l'étape 0 (faucheur) : périmée.
        assertThat(handler.handle(job(alice.getId(), id, 0)).reason()).isEqualTo("stale");
        jdbc.update("INSERT INTO async_jobs (id, user_id, kind, priority, payload, status, attempts, max_attempts, "
                + "available_at, created_at) VALUES (?,?,?,?,?,?,0,2,?,?)", UUID.randomUUID(), alice.getId(),
                CaptureReadJobHandler.KIND, 200, "{\"syncId\":\"" + id + "\",\"depositId\":\"x\"}", "PENDING",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

        JarvisStateSyncService.SyncView v = syncs.cancel(alice.getId(), host);
        assertThat(v.status()).isEqualTo(SyncRow.CANCELLED);
        Integer pending = jdbc.queryForObject("SELECT count(*) FROM async_jobs WHERE user_id = ? AND kind = ? AND "
                + "status = 'PENDING'", Integer.class, alice.getId(), CaptureReadJobHandler.KIND);
        assertThat(pending).isZero();
        SyncRow s = store.find(alice.getId(), id).orElseThrow();
        assertThat(handler.handle(job(alice.getId(), id, s.getStep())).reason()).isEqualTo("finished");
        assertThat(store.findActive(alice.getId(), host)).isEmpty();
    }

    @Test
    @DisplayName("API et isolation : 202 puis 200, liste du compte seulement, 404 hors compte, 409 registre coupé")
    void apiAndIsolation() throws Exception {
        String a = jwt.generateToken(alice);
        String b = jwt.generateToken(bob);
        mvc.perform(as(post("/api/jarvis/state/clients/" + host + "/sync"), b)).andExpect(status().isNotFound());
        mvc.perform(as(post("/api/jarvis/state/clients/" + host + "/sync"), a)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING")).andExpect(jsonPath("$.phases.length()").value(4))
                .andExpect(jsonPath("$.phases[0].key").value("HISTORY"))
                .andExpect(jsonPath("$.phases[1].key").value("NOTES"))
                .andExpect(jsonPath("$.phases[1].envelopeEur").value(20.0));
        mvc.perform(as(post("/api/jarvis/state/clients/" + host + "/sync"), a)).andExpect(status().isOk());
        mvc.perform(as(get("/api/jarvis/state/syncs"), a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].hostId").value(host.toString()));
        mvc.perform(as(get("/api/jarvis/state/syncs"), b)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(post("/api/jarvis/state/clients/" + host + "/sync/cancel"), b)).andExpect(status().isNotFound());
        mvc.perform(as(post("/api/jarvis/state/clients/" + host + "/sync/cancel"), a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(as(post("/api/jarvis/state/clients/" + host + "/sync/cancel"), a)).andExpect(status().isNotFound());
        // Un travail sous un autre compte ne trouve pas le passage.
        UUID id = syncs.start(alice.getId(), host).sync().id();
        assertThat(handler.handle(job(bob.getId(), id, 0)).reason()).isEqualTo("finished");
        syncs.cancel(alice.getId(), host);

        registry.setEnabled(alice.getId(), host, false);
        mvc.perform(as(post("/api/jarvis/state/clients/" + host + "/sync"), a)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("registry_off"));
    }
}
