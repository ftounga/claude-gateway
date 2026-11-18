package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.autonomy.AutonomyRunStatus;
import fr.claudegateway.atelier.autonomy.ChantierAutonomyRun;
import fr.claudegateway.atelier.autonomy.ChantierAutonomyRunRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-270 / SF-270-01 — l'état durable des travaux vivants : le registre F-272 étendu (tour direct annoncé sur un pod, pris
 * sur l'autre ; phase ; issues ; « oui » attendu clos par un nouveau travail), la lecture qui ajoute les courses
 * autonomes, l'API et l'isolation entre comptes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisWorksIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JarvisDelegationRepository repository;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private ChantierAutonomyRunRepository runRepository;
    @Autowired private JwtService jwtService;

    private final AtomicReference<Instant> now = new AtomicReference<>();
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private JarvisDelegationStore store;
    private JarvisDelegationRegistry podA;
    private JarvisDelegationRegistry podB;
    private JarvisWorksService works;
    private User alice;
    private User bob;
    private UUID subject;

    @BeforeEach
    void setUp() {
        now.set(Instant.now());
        store = new JarvisDelegationStore(repository, clock);
        podA = new JarvisDelegationRegistry(clock, null);
        podA.setStore(store);
        podB = new JarvisDelegationRegistry(clock, null);
        podB.setStore(store);
        works = new JarvisWorksService(store, workspaceRepository, runRepository, clock);
        alice = seedUser("works-alice-" + UUID.randomUUID() + "@ex.com");
        bob = seedUser("works-bob-" + UUID.randomUUID() + "@ex.com");
        subject = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("data-platform").build())
                .getId();
    }

    @Test
    @DisplayName("un tour direct annoncé sur le pod A est pris par le tour du pod B, une seule fois, puis clos")
    void aDirectTurnAnnouncedOnOnePodIsTakenOnTheOther() {
        UUID id = UUID.randomUUID();
        assertThat(store.direct(alice.getId(), id, subject, "data-platform", 4, "LDIC-325"))
                .isEqualTo(JarvisDelegationStore.Announced.CREATED);

        assertThat(podB.take(alice.getId(), subject, 4)).as("un tour direct n'est pas une demande reformulée").isNull();
        JarvisDelegationRegistry.Reformulation taken = podB.takeDirect(alice.getId(), subject, 4);
        assertThat(taken).isNotNull();
        assertThat(taken.delegationId()).isEqualTo(id);
        assertThat(taken.demande()).isNull();
        assertThat(podA.takeDirect(alice.getId(), subject, 4)).isNull();
        assertThat(podA.running(alice.getId())).extracting(JarvisDelegationRegistry.Running::delegationId)
                .containsExactly(id);

        podB.finished(alice.getId(), id, JarvisWorkPhase.DONE);
        assertThat(podA.running(alice.getId())).isEmpty();
        assertThat(store.direct(alice.getId(), id, subject, "data-platform", 4, "x"))
                .isEqualTo(JarvisDelegationStore.Announced.CONFLICT);
    }

    @Test
    @DisplayName("phase : réfléchit, puis exécute (une écriture) ; issues : fait, échec, annulé, attend un oui")
    void phasesAndOutcomes() {
        UUID running = offerAndTake(null, "le backlog");
        assertThat(phaseOf(running)).isEqualTo(JarvisWorkPhase.THINKING);
        assertThat(store.phase(alice.getId(), running, JarvisWorkPhase.EXECUTING)).isTrue();
        assertThat(store.phase(alice.getId(), running, JarvisWorkPhase.EXECUTING)).as("une seule écriture").isFalse();
        assertThat(phaseOf(running)).isEqualTo(JarvisWorkPhase.EXECUTING);

        UUID failed = offerAndTake(1, "a");
        UUID cancelled = offerAndTake(2, "b");
        UUID waiting = offerAndTake(3, "c");
        podB.finished(alice.getId(), failed, JarvisWorkPhase.FAILED);
        podB.finished(alice.getId(), cancelled, JarvisWorkPhase.CANCELLED);
        podB.finished(alice.getId(), waiting, JarvisWorkPhase.WAITING_YES);
        assertThat(phaseOf(failed)).isEqualTo(JarvisWorkPhase.FAILED);
        assertThat(phaseOf(cancelled)).isEqualTo(JarvisWorkPhase.CANCELLED);
        assertThat(phaseOf(waiting)).isEqualTo(JarvisWorkPhase.WAITING_YES);

        // 30 s plus tard : les fins ne se voient plus, le « oui » attendu si (2 min).
        now.set(now.get().plusSeconds(30));
        assertThat(works.list(alice.getId())).extracting(JarvisWorks.Work::id)
                .containsExactlyInAnyOrder(running.toString(), waiting.toString());
        now.set(now.get().plus(Duration.ofMinutes(2)));
        assertThat(works.list(alice.getId())).extracting(JarvisWorks.Work::id).containsExactly(running.toString());
    }

    @Test
    @DisplayName("un nouveau travail sur le fil clôt le « oui » attendu de ce fil, pas celui d'un autre fil")
    void aNewWorkOnTheThreadAnswersItsWaitingYes() {
        UUID waiting = offerAndTake(4, "Lancer LDIC-325");
        UUID other = offerAndTake(5, "Lancer LDIC-330");
        podB.finished(alice.getId(), waiting, JarvisWorkPhase.WAITING_YES);
        podB.finished(alice.getId(), other, JarvisWorkPhase.WAITING_YES);

        store.direct(alice.getId(), UUID.randomUUID(), subject, "data-platform", 4, "oui");

        assertThat(works.list(alice.getId())).filteredOn(w -> w.phase() == JarvisWorkPhase.WAITING_YES)
                .extracting(JarvisWorks.Work::name).containsExactly("LDIC-330");
    }

    @Test
    @DisplayName("la lecture ajoute les courses autonomes du compte (en cours = exécute), jamais celles d'un autre")
    void coursesAreRead() {
        runRepository.save(run(alice.getId(), subject, 7, "LDIC-331 — topic SNS", AutonomyRunStatus.RUNNING));
        UUID bobSubject = workspaceRepository.save(Workspace.builder().userId(bob.getId()).name("waf").build()).getId();
        runRepository.save(run(bob.getId(), bobSubject, 1, "WAF-1", AutonomyRunStatus.RUNNING));

        List<JarvisWorks.Work> list = works.list(alice.getId());

        assertThat(list).hasSize(1);
        assertThat(list.get(0).origin()).isEqualTo(JarvisWorks.Origin.COURSE);
        assertThat(list.get(0).name()).isEqualTo("LDIC-331");
        assertThat(list.get(0).phase()).isEqualTo(JarvisWorkPhase.EXECUTING);
        assertThat(list.get(0).subjectName()).isEqualTo("data-platform");
    }

    @Test
    @DisplayName("isolation : Bob ne prend, ne clôt ni ne lit rien d'Alice")
    void isolation() {
        UUID id = UUID.randomUUID();
        store.direct(alice.getId(), id, subject, "data-platform", null, "x");
        assertThat(podB.takeDirect(bob.getId(), subject, null)).isNull();
        podB.finished(bob.getId(), id, JarvisWorkPhase.FAILED);
        assertThat(store.phase(bob.getId(), id, JarvisWorkPhase.EXECUTING)).isFalse();
        assertThat(works.list(bob.getId())).isEmpty();
        assertThat(works.list(alice.getId())).extracting(JarvisWorks.Work::phase)
                .containsExactly(JarvisWorkPhase.THINKING);
    }

    @Test
    @DisplayName("API : POST annonce (201), sujet d'un autre compte 404, identifiant repris 409, sans jeton 401 ; GET lit")
    void api() throws Exception {
        String aliceToken = jwtService.generateToken(alice);
        String bobToken = jwtService.generateToken(bob);
        UUID id = UUID.randomUUID();
        String body = "{\"id\":\"" + id + "\",\"subjectId\":\"" + subject + "\",\"chantier\":4,\"label\":\"LDIC-325\"}";

        mockMvc.perform(post("/api/jarvis/works").contextPath("/api").header("Authorization", "Bearer " + bobToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/jarvis/works").contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/jarvis/works").contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/jarvis/works").contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mockMvc.perform(post("/api/jarvis/works").contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + subject + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/jarvis/works").contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.works[0].id").value(id.toString()))
                .andExpect(jsonPath("$.works[0].origin").value("DIRECT"))
                .andExpect(jsonPath("$.works[0].name").value("LDIC-325"))
                .andExpect(jsonPath("$.works[0].phase").value("THINKING"));
        mockMvc.perform(get("/api/jarvis/works").contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.works").isEmpty());
    }

    private UUID offerAndTake(Integer chantier, String label) {
        UUID id = UUID.randomUUID();
        podA.offer(alice.getId(), subject, chantier, id, "demande " + label, label, null);
        podA.started(alice.getId(), id, subject, "data-platform", chantier, label);
        assertThat(podB.take(alice.getId(), subject, chantier)).isNotNull();
        return id;
    }

    private JarvisWorkPhase phaseOf(UUID id) {
        return works.list(alice.getId()).stream().filter(w -> w.id().equals(id.toString())).findFirst()
                .map(JarvisWorks.Work::phase).orElse(null);
    }

    private ChantierAutonomyRun run(UUID user, UUID workspace, int chantier, String title, AutonomyRunStatus status) {
        OffsetDateTime at = OffsetDateTime.ofInstant(now.get(), ZoneOffset.UTC);
        return ChantierAutonomyRun.builder().userId(user).workspaceId(workspace)
                .chantierNumber(chantier).chantierTitle(title).status(status).startedAt(at).heartbeatAt(at).build();
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }
}
