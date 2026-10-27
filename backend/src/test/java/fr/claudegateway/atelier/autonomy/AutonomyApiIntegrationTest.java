package fr.claudegateway.atelier.autonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.atelier.AtelierChatService;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.journey.JourneyPlan;
import fr.claudegateway.atelier.journey.SubjectJourneyChantierRepository;
import fr.claudegateway.atelier.journey.SubjectJourneyEventRepository;
import fr.claudegateway.atelier.journey.SubjectJourneyRepository;
import fr.claudegateway.atelier.journey.SubjectJourneyService;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-219 / SF-219-02 — lancer un chantier en autonome sur une vraie base : départ, course jusqu'à l'arrêt,
 * refus (pas prêt, sujet occupé, limite, poste hors ligne), reprise en main, courses orphelines — et Bob ne
 * touche à rien. Le modèle est simulé ; tout le reste est réel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutonomyApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private SubjectJourneyRepository journeyRepository;
    @Autowired private SubjectJourneyChantierRepository chantierRepository;
    @Autowired private SubjectJourneyEventRepository eventRepository;
    @Autowired private SubjectJourneyService journeys;
    @Autowired private ChantierAutonomyRunRepository runs;
    @Autowired private AutonomyService service;
    @Autowired private LiveTurnRegistry liveTurns;
    @Autowired private fr.claudegateway.atelier.journey.JourneyToolExecutor journeyTools;

    @MockitoBean private AtelierChatService chat;

    private User alice;
    private String aliceToken;
    private String bobToken;
    private Workspace subject;
    private LiveTurn heldTurn;

    @BeforeEach
    void setUp() {
        runs.deleteAll();
        eventRepository.deleteAll();
        chantierRepository.deleteAll();
        journeyRepository.deleteAll();
        alice = userRepository.save(User.builder().email("auto-alice-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
        User bob = userRepository.save(User.builder().email("auto-bob-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);
        subject = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("ingress").build());
        when(chat.chatStreaming(any(), any(), anyString(), any())).thenReturn(new AtelierChatResult("ok", List.of(),
                UUID.randomUUID(), 10, 5, 3, false, new BigDecimal("0.20"), null));
    }

    @AfterEach
    void tearDown() {
        if (heldTurn != null) {
            liveTurns.close(heldTurn);
        }
        service.setDeferredTtl(java.time.Duration.ofMinutes(10));
    }

    private fr.claudegateway.atelier.journey.JourneyToolExecutor.Outcome launchTool(String json) throws Exception {
        return journeyTools.execute(alice.getId(), subject,
                fr.claudegateway.atelier.journey.JourneyToolCatalog.LAUNCH_AUTONOMOUS,
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
    }

    private void executing(Workspace ws) {
        UUID user = ws.getUserId();
        journeys.setMode(user, ws.getId(), "GUIDE", "Ingress");
        journeys.submitDiagnosis(user, ws.getId(), "cert expiré", "openssl", "ELEVEE");
        journeys.confirmDiagnosis(user, ws.getId());
        try {
            journeys.setPlan(user, ws.getId(), JourneyPlan.fromToolInput(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree("[{\"title\":\"Remplacer le certificat\",\"risk\":\"REVERSIBLE\"},"
                            + "{\"title\":\"Redémarrer\",\"risk\":\"EXTERNE\"}]")));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        journeys.validatePlan(user, ws.getId(), null);
    }

    private ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String token) throws Exception {
        return mockMvc.perform(request.contextPath("/api").header("Authorization", "Bearer " + token));
    }

    private String base(Workspace ws) {
        return "/api/workspaces/" + ws.getId() + "/journey";
    }

    private ChantierAutonomyRun awaitFinished() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<ChantierAutonomyRun> all = runs.findAll();
            if (!all.isEmpty() && all.get(0).getStatus() != AutonomyRunStatus.RUNNING) {
                return all.get(0);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("la course ne s'est pas terminée");
    }

    @Test
    @DisplayName("chantier prêt : 201, course écrite, le modèle reçoit la consigne ; sans avancée → BLOCKED")
    void launchRunsUntilBlocked() throws Exception {
        executing(subject);
        call(post(base(subject) + "/chantiers/1/autonomy"), aliceToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chantierNumber", is(1)))
                .andExpect(jsonPath("$.chantierTitle", is("Ingress")))
                .andExpect(jsonPath("$.stepsTotal", is(2)));
        ChantierAutonomyRun run = awaitFinished();
        assertThat(run.getStatus()).isEqualTo(AutonomyRunStatus.BLOCKED);
        assertThat(run.getStopReason()).startsWith("Aucune avancée");
        assertThat(run.getTurns()).isEqualTo(1);
        assertThat(run.getCostUsd()).isEqualByComparingTo("0.20");
        assertThat(run.getUserId()).isEqualTo(alice.getId());
        verify(chat).chatStreaming(eq(alice.getId()), eq(subject.getId()),
                org.mockito.ArgumentMatchers.contains("EXÉCUTION AUTONOME"), any());
        assertThat(liveTurns.find(alice.getId(), subject.getId())).isEmpty();

        call(get(base(subject) + "/autonomy"), aliceToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxParallel", is(4)))
                .andExpect(jsonPath("$.running", is(0)))
                .andExpect(jsonPath("$.chantiers[0].preflight.ready", is(true)))
                .andExpect(jsonPath("$.chantiers[0].run.status", is("BLOCKED")));
        call(get("/api/atelier/autonomy/runs"), aliceToken)
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].status", is("BLOCKED")));
        call(get("/api/atelier/autonomy/runs"), bobToken).andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    @DisplayName("pas prêt : 409 autonomy_not_ready avec les raisons ; chantier non ouvert : 400")
    void notReady() throws Exception {
        journeys.setMode(alice.getId(), subject.getId(), "GUIDE", "Ingress");
        call(post(base(subject) + "/chantiers/1/autonomy"), aliceToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("autonomy_not_ready")))
                .andExpect(jsonPath("$.reasons[0].code", is("PLAN_NOT_VALIDATED")));
        call(post(base(subject) + "/chantiers/9/autonomy"), aliceToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", startsWith("Le chantier 9")));
        assertThat(runs.count()).isZero();
    }

    @Test
    @DisplayName("sujet occupé par un tour : 409 autonomy_subject_busy")
    void busy() throws Exception {
        executing(subject);
        heldTurn = liveTurns.open(alice.getId(), subject.getId());
        call(post(base(subject) + "/chantiers/1/autonomy"), aliceToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("autonomy_subject_busy")));
    }

    @Test
    @DisplayName("quatre courses en cours : 409 autonomy_limit_reached")
    void limit() throws Exception {
        executing(subject);
        for (int i = 0; i < 4; i++) {
            Workspace other = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("s" + i).build());
            runs.save(ChantierAutonomyRun.builder().userId(alice.getId()).workspaceId(other.getId()).chantierNumber(1)
                    .status(AutonomyRunStatus.RUNNING).startedAt(OffsetDateTime.now())
                    .heartbeatAt(OffsetDateTime.now()).build());
        }
        call(post(base(subject) + "/chantiers/1/autonomy"), aliceToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("autonomy_limit_reached")))
                .andExpect(jsonPath("$.message", startsWith("Quatre chantiers autonomes")));
    }

    @Test
    @DisplayName("poste hors ligne : 409 autonomy_host_offline")
    void hostOffline() throws Exception {
        RunnerHost host = hostRepository.save(RunnerHost.builder().userId(alice.getId()).name("poste")
                .rootName("dev").os("linux").shell("posix").elevated(false)
                .lastSeenAt(OffsetDateTime.now().minusDays(2)).build());
        Workspace onHost = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("cagip")
                .hostId(host.getId()).projectPath("cagip").executionTarget(WorkspaceExecutionTarget.RUNNER).build());
        executing(onHost);
        call(post(base(onHost) + "/chantiers/1/autonomy"), aliceToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("autonomy_host_offline")));
    }

    @Test
    @DisplayName("reprendre la main : STOPPED tout de suite ; sans course : 409")
    void stop() throws Exception {
        executing(subject);
        runs.save(ChantierAutonomyRun.builder().userId(alice.getId()).workspaceId(subject.getId()).chantierNumber(1)
                .status(AutonomyRunStatus.RUNNING).startedAt(OffsetDateTime.now()).heartbeatAt(OffsetDateTime.now())
                .build());
        call(post(base(subject) + "/autonomy/stop"), bobToken).andExpect(status().isNotFound());
        call(post(base(subject) + "/autonomy/stop"), aliceToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("STOPPED")))
                .andExpect(jsonPath("$.stopReason", is("Reprise en main.")));
        verify(chat).interruptChat(alice.getId(), subject.getId());
        call(post(base(subject) + "/autonomy/stop"), aliceToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("autonomy_not_running")));
    }

    @Test
    @DisplayName("course orpheline : close en échec")
    void stale() {
        ChantierAutonomyRun old = runs.save(ChantierAutonomyRun.builder().userId(alice.getId())
                .workspaceId(subject.getId()).chantierNumber(1).status(AutonomyRunStatus.RUNNING)
                .startedAt(OffsetDateTime.now().minusHours(5)).heartbeatAt(OffsetDateTime.now().minusHours(4))
                .build());
        assertThat(service.closeStale()).isEqualTo(1);
        ChantierAutonomyRun closed = runs.findById(old.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(AutonomyRunStatus.FAILED);
        assertThat(closed.getErrorCode()).isEqualTo("orphaned");
        assertThat(service.closeStale()).isZero();
    }

    @Test
    @DisplayName("isolation : Bob ne lance ni ne lit rien chez Alice (404)")
    void isolation() throws Exception {
        executing(subject);
        call(post(base(subject) + "/chantiers/1/autonomy"), bobToken).andExpect(status().isNotFound());
        call(get(base(subject) + "/autonomy"), bobToken).andExpect(status().isNotFound());
        assertThat(runs.count()).isZero();
    }

    @Test
    @DisplayName("SF-219-04 : l'agent programme le départ ; il part quand le tour est fini, pas avant")
    void toolSchedulesThenLaunchesWhenIdle() throws Exception {
        executing(subject);
        heldTurn = liveTurns.open(alice.getId(), subject.getId()); // le tour qui appelle l'outil
        fr.claudegateway.atelier.journey.JourneyToolExecutor.Outcome out = launchTool("{\"chantier\":1}");
        assertThat(out.error()).isFalse();
        assertThat(out.content()).startsWith("Départ programmé : le chantier 1");
        assertThat(service.hasDeferred(alice.getId(), subject.getId())).isTrue();
        assertThat(service.launchDeferred()).isZero();
        assertThat(runs.count()).isZero();
        liveTurns.close(heldTurn);
        heldTurn = null;
        assertThat(service.launchDeferred()).isEqualTo(1);
        assertThat(service.hasDeferred(alice.getId(), subject.getId())).isFalse();
        assertThat(awaitFinished().getChantierNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("SF-219-04 : pas prêt → erreur avec les raisons, rien de programmé ; sans numéro → erreur")
    void toolRefusesWhenNotReady() throws Exception {
        journeys.setMode(alice.getId(), subject.getId(), "GUIDE", "Ingress");
        fr.claudegateway.atelier.journey.JourneyToolExecutor.Outcome out = launchTool("{\"chantier\":1}");
        assertThat(out.error()).isTrue();
        assertThat(out.content()).contains("n'est pas prêt").contains("Aucun plan");
        assertThat(service.hasDeferred(alice.getId(), subject.getId())).isFalse();
        assertThat(launchTool("{}").error()).isTrue();
    }

    @Test
    @DisplayName("SF-219-04 : un départ expiré ne part pas ; un préflight devenu rouge est abandonné")
    void deferredExpiresOrIsAbandoned() throws Exception {
        executing(subject);
        service.setDeferredTtl(java.time.Duration.ofMillis(-1));
        assertThat(launchTool("{\"chantier\":1}").error()).isFalse();
        assertThat(service.launchDeferred()).isZero();
        assertThat(service.hasDeferred(alice.getId(), subject.getId())).isFalse();

        service.setDeferredTtl(java.time.Duration.ofMinutes(10));
        assertThat(launchTool("{\"chantier\":1}").error()).isFalse();
        journeys.setPlan(alice.getId(), subject.getId(), JourneyPlan.fromToolInput(
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                        "[{\"title\":\"Autre chose\",\"risk\":\"REVERSIBLE\"}]"))); // amendement
        assertThat(service.launchDeferred()).isZero();
        assertThat(service.hasDeferred(alice.getId(), subject.getId())).isFalse();
        assertThat(runs.count()).isZero();
    }
}
