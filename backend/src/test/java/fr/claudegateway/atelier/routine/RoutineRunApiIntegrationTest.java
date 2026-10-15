package fr.claudegateway.atelier.routine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-183 / SF-183-02 — le journal des exécutions sur une vraie base : verrou du créneau (un seul gagnant),
 * créneau manqué noté une fois, orphelines closes, historique isolé, « Lancer maintenant » refusé poste
 * hors ligne.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoutineRunApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private SubjectRoutineRepository routineRepository;
    @Autowired private RoutineRunRepository runRepository;
    @Autowired private RoutineRunService runService;
    @Autowired private PlatformTransactionManager transactionManager;

    private User alice;
    private String aliceToken;
    private String bobToken;
    private Workspace subject;
    private SubjectRoutine routine;

    @BeforeEach
    void setUp() {
        runRepository.deleteAll();
        routineRepository.deleteAll();
        alice = userRepository.save(User.builder().email("run-alice-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
        User bob = userRepository.save(User.builder().email("run-bob-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);
        RunnerHost host = hostRepository.save(RunnerHost.builder().userId(alice.getId()).name("poste")
                .rootName("dev").os("linux").shell("posix").elevated(false).lastSeenAt(OffsetDateTime.now())
                .build());
        subject = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("cagip")
                .hostId(host.getId()).projectPath("cagip").executionTarget(WorkspaceExecutionTarget.RUNNER)
                .build());
        OffsetDateTime now = OffsetDateTime.now();
        routine = routineRepository.save(SubjectRoutine.builder().userId(alice.getId())
                .workspaceId(subject.getId()).name("Point du matin").instruction("CR du daily")
                .cadence(RoutineCadence.WEEKDAYS).runTime("08:00").timeZone("Europe/Paris")
                .maxRunUsd(new BigDecimal("5.00")).origin(RoutineOrigin.USER).active(true)
                .lastSlotAt(OffsetDateTime.parse("2026-10-16T08:00:00+02:00")).createdAt(now).updatedAt(now)
                .build());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    private String base() {
        return "/api/workspaces/" + subject.getId() + "/routines/" + routine.getId();
    }

    @Test
    void theSlotLockHasASingleWinner() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        OffsetDateTime slot = OffsetDateTime.parse("2026-10-19T08:00:00+02:00");
        Integer first = tx.execute(s -> routineRepository.claimSlot(routine.getId(), slot));
        Integer second = tx.execute(s -> routineRepository.claimSlot(routine.getId(), slot));
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(routineRepository.findById(routine.getId()).orElseThrow().getLastSlotAt()).isEqualTo(slot);
    }

    @Test
    void aMissedSlotIsNotedOnce() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        OffsetDateTime slot = OffsetDateTime.parse("2026-10-19T08:00:00+02:00");
        Integer first = tx.execute(s -> routineRepository.markMissed(routine.getId(), slot));
        Integer again = tx.execute(s -> routineRepository.markMissed(routine.getId(), slot));
        assertThat(first).isEqualTo(1);
        assertThat(again).isZero();
        // Le créneau manqué n'est pas consommé : il reste disponible pour le rattrapage.
        Integer claimed = tx.execute(s -> routineRepository.claimSlot(routine.getId(), slot));
        assertThat(claimed).isEqualTo(1);
        assertThat(routineRepository.findById(routine.getId()).orElseThrow().getMissedSlotAt()).isNull();
    }

    @Test
    void orphanRunsAreClosedSoTheDeployCheckIsNotFooled() {
        RoutineRun run = runService.open(routine, RoutineTrigger.SCHEDULED, null, UUID.randomUUID(), null);
        run.setStartedAt(OffsetDateTime.now().minusHours(4));
        runRepository.save(run);
        RoutineRun fresh = runService.open(routine, RoutineTrigger.MANUAL, null, UUID.randomUUID(), null);
        assertThat(runService.closeStale(Duration.ofHours(3))).isEqualTo(1);
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RoutineRunStatus.FAILED);
        assertThat(runRepository.findById(run.getId()).orElseThrow().getErrorCode()).isEqualTo("interrupted");
        assertThat(runRepository.findById(fresh.getId()).orElseThrow().getStatus())
                .isEqualTo(RoutineRunStatus.RUNNING);
    }

    @Test
    void historyAndLastRunAreIsolated() throws Exception {
        runService.recordClosed(routine, RoutineTrigger.SCHEDULED, OffsetDateTime.now(),
                RoutineRunStatus.MISSED_OFFLINE, "host_offline");
        mockMvc.perform(as(get(base() + "/runs"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("MISSED_OFFLINE"))
                .andExpect(jsonPath("$[0].finishedAt").isNotEmpty());
        mockMvc.perform(as(get("/api/workspaces/" + subject.getId() + "/routines"), aliceToken))
                .andExpect(jsonPath("$[0].lastRun.status").value("MISSED_OFFLINE"));
        mockMvc.perform(as(get(base() + "/runs"), bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(as(post(base() + "/run"), bobToken)).andExpect(status().isNotFound());
    }

    @Test
    void monthSpentIsVisibleAndAnExhaustedMonthRefusesARun() throws Exception {
        RoutineRun run = runService.open(routine, RoutineTrigger.SCHEDULED, null, UUID.randomUUID(),
                new BigDecimal("5.00"));
        runService.finish(run.getId(), RoutineRunStatus.DONE, new BigDecimal("3.42"), null);
        mockMvc.perform(as(get("/api/workspaces/" + subject.getId() + "/routines"), aliceToken))
                .andExpect(jsonPath("$[0].monthlyBudgetUsd").value(100.0))
                .andExpect(jsonPath("$[0].monthSpentUsd").value(3.42));

        RoutineRun big = runService.open(routine, RoutineTrigger.SCHEDULED, null, UUID.randomUUID(), null);
        runService.finish(big.getId(), RoutineRunStatus.DONE, new BigDecimal("96.58"), null);
        mockMvc.perform(as(post(base() + "/run"), aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("routine_budget_exhausted"));
    }

    @Test
    void runNowRefusesWhenTheHostIsOffline() throws Exception {
        // Aucun battement de runner : le poste est hors ligne.
        mockMvc.perform(as(post(base() + "/run"), aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("routine_host_offline"));
        assertThat(runRepository.count()).isZero();
    }
}
