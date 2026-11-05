package fr.claudegateway.jarvis.state.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateVocabulary;
import fr.claudegateway.jarvis.state.extract.StateNotesGuard;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.shared.jobs.AsyncJobQueue;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-247 / SF-247-17 — « Constats sur l'historique » dans « Synchroniser » : seules les sorties utiles partent (ni
 * fichiers, ni secret, ni couvertes), la garde de citation reste obligatoire, date d'origine, jamais d'écrasement par
 * plus ancien, idempotence par empreinte, enveloppe ponctuelle hors plafond mensuel, cession aux tours, isolation, API.
 */
@SpringBootTest(properties = "app.jobs.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisStateSyncNotesIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @MockitoBean private RunnerLiveness liveness;
    @MockitoBean private AiAgentProvider provider;

    @Autowired private JarvisStateSyncService syncs;
    @Autowired private JarvisStateSyncStore store;
    @Autowired private StateSyncJobHandler handler;
    @Autowired private JarvisStateRegistry registry;
    @Autowired private AtelierMessageRepository messages;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private UserRepository users;
    @Autowired private LiveTurnRegistry liveTurns;
    @Autowired private JarvisUsageService usage;
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
                .shell("posix").elevated(false).runnerCapabilities("files,bash").build()).getId();
        subject = workspaces.save(Workspace.builder().userId(alice.getId()).name("SSO").hostId(host)
                .executionTarget(WorkspaceExecutionTarget.RUNNER).createdAt(OffsetDateTime.now()).build());
        when(liveness.isAlive(any(), any())).thenReturn(false);
        when(provider.nextTurn(any())).thenReturn(new AgentTurn("{\"faits\":[]}", List.of(), true, 2_000, 120));
    }

    private User user() {
        return users.save(User.builder().email("syncnotes-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build());
    }

    private static ObjectNode block(String toolUseId, String command, String output) {
        ObjectNode b = MAPPER.createObjectNode();
        b.put("tool", "bash");
        b.put("command", command);
        b.put("toolUseId", toolUseId);
        b.put("output", "$ " + command + "\n" + output + "\n[code de sortie: 0]");
        b.put("error", false);
        return b;
    }

    /** Une réponse ancienne dont les sorties sont rendues à {@code toolAt} (journal du runner). */
    private AtelierMessage oldTurn(UUID userId, Workspace w, OffsetDateTime toolAt, String[]... commandAndOutput) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode blocks = root.putArray("blocks");
        for (String[] co : commandAndOutput) {
            String toolUseId = "toolu_" + UUID.randomUUID();
            blocks.add(block(toolUseId, co[0], co[1]));
            jdbc.update("INSERT INTO runner_audit (id, user_id, workspace_id, call_id, tool, outcome, created_at) "
                    + "VALUES (?,?,?,?,?,?,?)", UUID.randomUUID(), userId, w.getId(), toolUseId, "bash", "ok",
                    Timestamp.from(toolAt.toInstant()));
        }
        OffsetDateTime at = toolAt.plusMinutes(1);
        AtelierMessage m = messages.save(AtelierMessage.builder().workspaceId(w.getId()).userId(userId)
                .role("ASSISTANT").content("ok").terminalJson(root.toString()).createdAt(at).build());
        jdbc.update("UPDATE atelier_messages SET created_at = ? WHERE id = ?", Timestamp.from(at.toInstant()), m.getId());
        return m;
    }

    private SyncRow drive(UUID userId, UUID syncId, int max) {
        for (int i = 0; i < max; i++) {
            SyncRow s = store.find(userId, syncId).orElseThrow();
            if (!s.active()) {
                return s;
            }
            handler.handle(new AsyncJobQueue.Job(UUID.randomUUID(), userId, StateSyncJobHandler.KIND,
                    StateSyncJobHandler.PRIORITY, StateSyncJobHandler.payload(syncId, s.getStep()), null, 1, 3,
                    Instant.now()));
        }
        return store.find(userId, syncId).orElseThrow();
    }

    private static final String PY = "cd ~/dev && python3 .outils/jira.py voir PROJ-7";
    private static final String PY_OUT = "PROJ-7 | Migration SSO | En revue | maj 2026-10-20";

    @Test
    @DisplayName("Constats : sorties utiles seules, citation vérifiée, date d'origine, plus récent jamais écrasé, "
            + "coût hors plafond mensuel, idempotent")
    void notesOnHistory() {
        OffsetDateTime tenDays = OffsetDateTime.now().minusDays(10).truncatedTo(ChronoUnit.SECONDS);
        oldTurn(alice.getId(), subject, tenDays,
                new String[] {PY, PY_OUT},
                new String[] {"grep -n status src/App.java", "12: status = computeStatus();"},
                new String[] {"printf 'host=h\\n' | git credential fill", "protocol=https\nhost=h\npassword=abc"});
        oldTurn(alice.getId(), subject, tenDays.plusHours(2),
                new String[] {"python3 .outils/etat.py web", "web | état : Ancien | version 1.2"});
        // Un fait plus récent est déjà au registre pour « web » : le rattrapage ne doit pas le remplacer.
        registry.record(alice.getId(), List.of(new JarvisStateRegistry.Observation(host, subject.getId(), "OTHER",
                "NOTE:web", "state", "Actuel", OffsetDateTime.now(), JarvisStateVocabulary.SOURCE_NOTE, "x",
                JarvisStateVocabulary.MOYENNE)));
        when(provider.nextTurn(any())).thenAnswer(inv -> {
            AgentTurnRequest r = inv.getArgument(0);
            String text = r.messages().toString();
            if (text.contains("PROJ-7")) {
                return new AgentTurn("{\"faits\":[{\"sortie\":1,\"objet\":\"PROJ-7\",\"famille\":\"TICKET\","
                        + "\"attribut\":\"status\",\"valeur\":\"En revue\",\"citation\":\"PROJ-7 | Migration SSO | En revue\"},"
                        + "{\"sortie\":1,\"objet\":\"PROJ-7\",\"famille\":\"TICKET\",\"attribut\":\"assignee\","
                        + "\"valeur\":\"M. Martin\",\"citation\":\"assigné à M. Martin\"},"
                        + "{\"sortie\":1,\"objet\":\"PROJ-7\",\"famille\":\"TICKET\",\"attribut\":\"title\","
                        + "\"valeur\":\"SSO\",\"citation\":\"Migration du SSO\"}]}", List.of(), true, 2_000, 120);
            }
            return new AgentTurn("{\"faits\":[{\"sortie\":1,\"objet\":\"web\",\"famille\":\"OTHER\",\"attribut\":"
                    + "\"state\",\"valeur\":\"Ancien\",\"citation\":\"état : Ancien\"}]}", List.of(), true, 2_000, 120);
        });

        JarvisStateSyncService.Started started = syncs.start(alice.getId(), host);
        SyncRow s = drive(alice.getId(), started.sync().id(), 20);

        assertThat(s.getStatus()).isEqualTo(SyncRow.DONE);
        assertThat(s.getP4Status()).isEqualTo(SyncRow.P_DONE);
        assertThat(s.getP4Total()).isEqualTo(2); // la sortie de fichiers et la lecture d'identifiant ne comptent pas
        assertThat(s.getP4Done()).isEqualTo(2);
        assertThat(s.getP4Calls()).isEqualTo(2);
        assertThat(s.getP4Kept()).isEqualTo(2);
        assertThat(s.getP4Rejected()).isEqualTo(2); // citation absente de la sortie, citation reformulée
        assertThat(s.getP4Facts()).isEqualTo(1); // « web » plus récent en base : non écrasé
        assertThat(s.getP4CostUsd().signum()).isPositive();
        assertThat(s.getP4EnvelopeEur()).isEqualByComparingTo("20");

        ArgumentCaptor<AgentTurnRequest> sent = ArgumentCaptor.forClass(AgentTurnRequest.class);
        verify(provider, times(2)).nextTurn(sent.capture());
        for (AgentTurnRequest r : sent.getAllValues()) {
            assertThat(r.system()).isEqualTo(StateNotesGuard.SYSTEM); // consigne inchangée
            assertThat(r.messages().toString()).doesNotContain("computeStatus").doesNotContain("password");
        }

        JarvisStateRegistry.Fact f = registry.byKey(alice.getId(), host, "NOTE:PROJ-7", 5).get(0);
        assertThat(f.value()).isEqualTo("En revue");
        assertThat(f.source()).isEqualTo("NOTE");
        assertThat(f.confidence()).isEqualTo("MOYENNE");
        assertThat(f.observedAt().toInstant()).isEqualTo(tenDays.toInstant());
        assertThat(registry.byKey(alice.getId(), host, "NOTE:web", 5).get(0).value()).isEqualTo("Actuel");
        // Le coût est au budget Jarvis, hors plafond mensuel des constats au fil de l'eau.
        assertThat(usage.monthNotesCostUsd(alice.getId())).isEqualByComparingTo(BigDecimal.ZERO);
        BigDecimal catchup = jdbc.queryForObject("SELECT COALESCE(sum(notes_catchup_cost_usd), 0) FROM "
                + "jarvis_usage_days WHERE user_id = ?", BigDecimal.class, alice.getId());
        assertThat(catchup.signum()).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jarvis_state_notes_seen WHERE user_id = ? AND host_id = ?",
                Integer.class, alice.getId(), host)).isEqualTo(2);

        // Relancer : rien n'est relu (empreintes), aucun appel de plus.
        SyncRow again = drive(alice.getId(), syncs.start(alice.getId(), host).sync().id(), 20);
        assertThat(again.getP4Status()).isEqualTo(SyncRow.P_DONE);
        assertThat(again.getP4Detail()).isEqualTo("nothing");
        assertThat(again.getP4Calls()).isZero();
        verify(provider, times(2)).nextTurn(any());
    }

    @Test
    @DisplayName("Enveloppe : jamais dépassée (arrêt avant l'appel) ; 0 € = phase sautée")
    void envelope() {
        oldTurn(alice.getId(), subject, OffsetDateTime.now().minusDays(3), new String[] {PY, PY_OUT});
        UUID id = syncs.start(alice.getId(), host, new BigDecimal("0.01")).sync().id();
        drive(alice.getId(), id, 1); // historique (+ estimation)
        // Le passage a déjà presque tout dépensé : l'appel suivant, au pire, crèverait l'enveloppe.
        jdbc.update("UPDATE jarvis_state_syncs SET p4_cost_usd = 0.0108 WHERE id = ?", id);
        SyncRow s = drive(alice.getId(), id, 20);
        assertThat(s.getP4Status()).isEqualTo(SyncRow.P_DONE);
        assertThat(s.getP4Detail()).isEqualTo("envelope");
        assertThat(s.getP4Total()).isEqualTo(1);
        assertThat(s.getP4Calls()).isZero();

        SyncRow zero = drive(alice.getId(), syncs.start(alice.getId(), host, BigDecimal.ZERO).sync().id(), 20);
        assertThat(zero.getP4Status()).isEqualTo(SyncRow.P_SKIPPED);
        assertThat(zero.getP4Detail()).isEqualTo("envelope_zero");
        verify(provider, never()).nextTurn(any());
    }

    @Test
    @DisplayName("Tour en cours : la phase des constats se met en pause, aucun appel, puis reprend")
    void yieldsToTurns() {
        oldTurn(alice.getId(), subject, OffsetDateTime.now().minusDays(3), new String[] {PY, PY_OUT});
        UUID id = syncs.start(alice.getId(), host).sync().id();
        SyncRow s = drive(alice.getId(), id, 1); // historique (+ estimation)
        assertThat(s.getPhase()).isEqualTo(SyncRow.PHASE_NOTES);
        LiveTurn turn = liveTurns.open(alice.getId(), subject.getId());
        try {
            s = drive(alice.getId(), id, 1);
            assertThat(s.getNote()).isEqualTo(SyncRow.NOTE_TURN);
            assertThat(s.getP4Status()).isEqualTo(SyncRow.P_PAUSED);
            verify(provider, never()).nextTurn(any());
        } finally {
            liveTurns.close(turn);
        }
        s = drive(alice.getId(), id, 10);
        assertThat(s.getP4Status()).isEqualTo(SyncRow.P_DONE);
        verify(provider, times(1)).nextTurn(any());
    }

    @Test
    @DisplayName("Isolation et API : l'historique d'un autre compte n'est jamais lu ; enveloppe demandée et affichée")
    void isolationAndApi() throws Exception {
        oldTurn(alice.getId(), subject, OffsetDateTime.now().minusDays(3), new String[] {PY, PY_OUT});
        UUID bobHost = hosts.save(RunnerHost.builder().userId(bob.getId()).name("EDENRED").rootName("dev").os("linux")
                .shell("posix").elevated(false).build()).getId();
        SyncRow b = drive(bob.getId(), syncs.start(bob.getId(), bobHost).sync().id(), 20);
        assertThat(b.getP4Total()).isZero();
        verify(provider, never()).nextTurn(any());

        String a = jwt.generateToken(alice);
        mvc.perform(as(get("/api/jarvis/state/syncs/envelope"), a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.notesEnvelopeEur").value(20.0))
                .andExpect(jsonPath("$.notesMonthlyCapEur").value(15.0));
        mvc.perform(as(post("/api/jarvis/state/clients/" + host + "/sync"), a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"notesEnvelopeEur\":5}")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.phases[1].key").value("NOTES"))
                .andExpect(jsonPath("$.phases[1].envelopeEur").value(5.0));
        mvc.perform(as(post("/api/jarvis/state/clients/" + host + "/sync"), jwt.generateToken(bob))
                .contentType(MediaType.APPLICATION_JSON).content("{\"notesEnvelopeEur\":5}"))
                .andExpect(status().isNotFound());
        List<String> none = new ArrayList<>(jdbc.queryForList("SELECT fingerprint FROM jarvis_state_notes_seen WHERE "
                + "user_id = ?", String.class, bob.getId()));
        assertThat(none).isEmpty();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String token) {
        return r.contextPath("/api").header("Authorization", "Bearer " + token);
    }
}
