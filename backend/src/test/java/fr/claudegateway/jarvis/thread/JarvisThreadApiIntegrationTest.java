package fr.claudegateway.jarvis.thread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.universe.JarvisUniverseService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-245 / SF-245-01 — <b>le fil Jarvis existe</b> : un par compte (même sous deux créations concurrentes), en tête de
 * l'Atelier, absent de l'univers et des projets « Hébergé », ni renommable ni supprimable (400), invisible d'autrui
 * (404).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisThreadApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private JarvisThreadService threads;
    @Autowired private JarvisUniverseService universe;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper json;

    private String aliceToken;
    private String bobToken;
    private UUID aliceId;
    private UUID bobId;

    @BeforeEach
    void setUp() {
        User alice = seedUser("jarvis-thread-alice-" + UUID.randomUUID() + "@ex.com");
        User bob = seedUser("jarvis-thread-bob-" + UUID.randomUUID() + "@ex.com");
        aliceId = alice.getId();
        bobId = bob.getId();
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
    }

    @Test
    @DisplayName("GET /jarvis/thread crée le fil une fois, puis rend le même ; nommé « Jarvis », sans poste, SANDBOX")
    void createdOnceThenSame() throws Exception {
        UUID first = threadId(aliceToken);
        UUID second = threadId(aliceToken);
        assertThat(second).isEqualTo(first);
        Workspace thread = workspaceRepository.findByIdAndUserId(first, aliceId).orElseThrow();
        assertThat(thread.isJarvisThread()).isTrue();
        assertThat(thread.getName()).isEqualTo(JarvisThreadService.NAME);
        assertThat(thread.getHostId()).isNull();
        assertThat(thread.executionTargetOrDefault()).isEqualTo(WorkspaceExecutionTarget.SANDBOX);
        assertThat(thread.isHostTerminal()).isFalse();
        assertThat(thread.isTeamsTerminal()).isFalse();
    }

    @Test
    @DisplayName("critère 1 : huit créations concurrentes ne créent qu'un fil")
    void concurrentCreationMakesOne() throws Exception {
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<UUID>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Callable<UUID> call = () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return threads.open(aliceId).getId();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            Set<UUID> ids = new HashSet<>();
            for (Future<UUID> result : results) {
                ids.add(result.get(20, TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        long count = workspaceRepository.findByUserIdOrderByCreatedAtDesc(aliceId).stream()
                .filter(Workspace::isJarvisThread).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("critère 2 : le fil n'est ni dans l'univers du cerveau rapide, ni parmi les projets « Hébergé »")
    void excludedFromUniverseAndHosted() throws Exception {
        UUID id = threadId(aliceToken);
        workspaceRepository.save(Workspace.builder().userId(aliceId).name("Doc archi")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build());

        assertThat(universe.universe(aliceId).clients()).allSatisfy(client -> assertThat(client.subjects())
                .noneMatch(subject -> id.equals(subject.id())));
        assertThat(universe.universe(aliceId).clients()).anySatisfy(client -> assertThat(client.subjects())
                .anyMatch(subject -> "Doc archi".equals(subject.name())));
        assertThat(workspaceService.listWithoutHost(aliceId)).extracting(Workspace::getId).doesNotContain(id);
        perform(aliceToken, get("/api/jarvis/universe")).andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(id.toString()));
    }

    @Test
    @DisplayName("critère 3 : en tête de la liste de l'Atelier, marqué jarvisThread")
    void firstInAtelierList() throws Exception {
        workspaceRepository.save(Workspace.builder().userId(aliceId).name("Plus récent")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build());
        UUID id = threadId(aliceToken);
        workspaceRepository.save(Workspace.builder().userId(aliceId).name("Encore plus récent")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build());

        perform(aliceToken, get("/api/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].jarvisThread").value(true))
                .andExpect(jsonPath("$[1].jarvisThread").value(false))
                .andExpect(jsonPath("$[2].jarvisThread").value(false));
    }

    @Test
    @DisplayName("critère 4 : supprimer, renommer, rattacher ou changer la cible du fil donne 400 ; il reste en place")
    void notDeletableNorRenamable() throws Exception {
        UUID id = threadId(aliceToken);

        perform(aliceToken, delete("/api/workspaces/" + id)).andExpect(status().isBadRequest());
        perform(aliceToken, post("/api/workspaces/" + id + "/rename").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Autre\"}")).andExpect(status().isBadRequest());
        perform(aliceToken, put("/api/workspaces/" + id + "/execution-target").contentType(MediaType.APPLICATION_JSON)
                .content("{\"executionTarget\":\"RUNNER\"}")).andExpect(status().isBadRequest());

        Workspace thread = workspaceRepository.findByIdAndUserId(id, aliceId).orElseThrow();
        assertThat(thread.getName()).isEqualTo(JarvisThreadService.NAME);
        assertThat(thread.executionTargetOrDefault()).isEqualTo(WorkspaceExecutionTarget.SANDBOX);
    }

    @Test
    @DisplayName("critère 5 : isolation — Bob reçoit 404 sur le fil d'Alice et a son propre fil")
    void isolation() throws Exception {
        UUID alices = threadId(aliceToken);

        perform(bobToken, get("/api/workspaces/" + alices)).andExpect(status().isNotFound());
        perform(bobToken, delete("/api/workspaces/" + alices)).andExpect(status().isNotFound());
        perform(bobToken, get("/api/workspaces/" + alices + "/chat")).andExpect(status().isNotFound());
        perform(bobToken, get("/api/workspaces")).andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(alices.toString()));

        UUID bobs = threadId(bobToken);
        assertThat(bobs).isNotEqualTo(alices);
        assertThat(threads.find(bobId).map(Workspace::getId)).contains(bobs);
        assertThat(threads.find(aliceId).map(Workspace::getId)).contains(alices);
    }

    @Test
    @DisplayName("purge du compte : le fil part avec le reste (la garde de suppression ne vise que l'écran)")
    void purgedWithAccount() {
        Workspace thread = threads.open(aliceId);
        workspaceService.delete(aliceId, thread.getId());
        assertThat(threads.find(aliceId)).isEmpty();
    }

    private UUID threadId(String token) throws Exception {
        String body = perform(token, get("/api/jarvis/thread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(JarvisThreadService.NAME))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return UUID.fromString(node.get("id").asText());
    }

    private ResultActions perform(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.contextPath("/api").header("Authorization", "Bearer " + token));
    }

    /** ADMIN : la Forge est ouverte d'office (le droit d'accès n'est pas l'objet de ce test). */
    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }
}
