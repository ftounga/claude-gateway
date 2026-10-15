package fr.claudegateway.atelier.routine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * F-183 / SF-183-01 — l'API des routines sous isolation : le propriétaire déclare, relit, modifie,
 * met en pause, valide une proposition et supprime ; un autre utilisateur reçoit 404 partout ; un sujet
 * hébergé refuse.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoutineApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private SubjectRoutineRepository routineRepository;
    @Autowired private RoutineService routineService;

    private User alice;
    private String aliceToken;
    private String bobToken;
    private Workspace subject;

    @BeforeEach
    void setUp() {
        routineRepository.deleteAll();
        alice = userRepository.save(User.builder().email("routine-alice-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
        User bob = userRepository.save(User.builder().email("routine-bob-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);
        RunnerHost host = hostRepository.save(RunnerHost.builder().userId(alice.getId()).name("poste")
                .rootName("dev").os("linux").shell("posix").elevated(false).lastSeenAt(OffsetDateTime.now())
                .build());
        subject = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("cagip")
                .hostId(host.getId()).projectPath("cagip").executionTarget(WorkspaceExecutionTarget.RUNNER)
                .build());
    }

    private String base() {
        return "/api/workspaces/" + subject.getId() + "/routines";
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    private static final String BODY = """
            {"name":"Point du matin","instruction":"le CR du daily d'aujourd'hui, version scriptée",
             "cadence":"WEEKDAYS","runTime":"08:00","timeZone":"Europe/Paris"}""";

    @Test
    void ownerManagesARoutineEndToEnd() throws Exception {
        String created = mockMvc.perform(as(post(base()), aliceToken).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cadence").value("WEEKDAYS"))
                .andExpect(jsonPath("$.maxRunUsd").value(5.0))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(as(get(base()), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Point du matin"));

        mockMvc.perform(as(put(base() + "/" + id), aliceToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false,\"maxRunUsd\":2.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.maxRunUsd").value(2.5))
                .andExpect(jsonPath("$.nextRunAt").doesNotExist());

        mockMvc.perform(as(delete(base() + "/" + id), aliceToken)).andExpect(status().isNoContent());
        assertThat(routineRepository.count()).isZero();
    }

    @Test
    void validationErrorsHaveStableCodes() throws Exception {
        mockMvc.perform(as(post(base()), aliceToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"instruction\":\"y\",\"cadence\":\"WEEKLY\",\"runTime\":\"08:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("routine_invalid"));
        mockMvc.perform(as(post(base()), aliceToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"instruction\":\"y\",\"cadence\":\"DAILY\",\"runTime\":\"25:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("routine_invalid"));
    }

    @Test
    void hostedSubjectRefuses() throws Exception {
        Workspace hosted = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("hosted")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build());
        mockMvc.perform(as(post("/api/workspaces/" + hosted.getId() + "/routines"), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("routine_unsupported"));
    }

    @Test
    void proposalIsAcceptedOrRefused() throws Exception {
        UUID proposed = routineService.propose(alice.getId(), subject, new RoutineDtos.RoutineRequest("Proposée",
                "CR", "DAILY", null, "08:00", null, null, null)).id();
        mockMvc.perform(as(get(base()), aliceToken))
                .andExpect(jsonPath("$[0].proposed").value(true))
                .andExpect(jsonPath("$[0].origin").value("AGENT"));
        mockMvc.perform(as(post(base() + "/" + proposed + "/accept"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposed").value(false))
                .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(as(post(base() + "/" + proposed + "/accept"), aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("routine_conflict"));
    }

    @Test
    void anotherUserGets404Everywhere() throws Exception {
        String created = mockMvc.perform(as(post(base()), aliceToken).contentType(MediaType.APPLICATION_JSON)
                .content(BODY)).andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(created);
        String id = node.get("id").asText();

        mockMvc.perform(as(get(base()), bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(as(post(base()), bobToken).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(put(base() + "/" + id), bobToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).andExpect(status().isNotFound());
        mockMvc.perform(as(post(base() + "/" + id + "/accept"), bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(as(delete(base() + "/" + id), bobToken)).andExpect(status().isNotFound());
        assertThat(routineRepository.findById(UUID.fromString(id))).get()
                .extracting(SubjectRoutine::isActive).isEqualTo(true);
    }
}
