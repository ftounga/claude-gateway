package fr.claudegateway.atelier;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.WorkspaceService.CreatedWorkspace;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de l'Atelier (F-28 / SF-28-02) : la boucle tool-use exécute réellement les
 * outils fichiers sur le workspace, sous isolation {@code user_id}. Le fournisseur d'agent est un stub
 * scriptable (aucun réseau).
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AtelierChatApiIntegrationTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubAiAgentProvider stubAiAgentProvider() {
            return new StubAiAgentProvider();
        }

        /** Exécuteur SSE synchrone : le relais s'exécute au retour du contrôleur (corps lisible direct). */
        @Bean("chatStreamExecutor")
        @Primary
        java.util.concurrent.Executor chatStreamExecutor() {
            return Runnable::run;
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private AtelierMessageRepository atelierMessageRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private StubAiAgentProvider stub;

    private User alice;
    private String aliceToken;
    private User bob;
    private String bobToken;

    @BeforeEach
    void setUp() {
        atelierMessageRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
        stub.reset();
        alice = userRepository.save(User.builder().email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
        bob = userRepository.save(User.builder().email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private UUID createWorkspace(User user, String file, String content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry(file));
            zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        CreatedWorkspace created = workspaceService.create(user.getId(), "projet", baos.toByteArray());
        return created.workspace().getId();
    }

    @Test
    void agentLoopReadsAndWritesFilesThenAnswers() throws Exception {
        UUID ws = createWorkspace(alice, "notes.txt", "contenu initial");
        stub.enqueueToolCall("read_file", "path", "notes.txt");
        stub.enqueueToolCall("write_file", "path", "notes.txt", "content", "contenu mis à jour");
        stub.enqueueFinal("J'ai mis à jour notes.txt.");

        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"mets à jour notes.txt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply", containsString("mis à jour")))
                .andExpect(jsonPath("$.actions[?(@.type=='read')].path", org.hamcrest.Matchers.hasItem("notes.txt")))
                .andExpect(jsonPath("$.actions[?(@.type=='write')].path", org.hamcrest.Matchers.hasItem("notes.txt")));

        // Le fichier a réellement été modifié dans le workspace.
        org.assertj.core.api.Assertions.assertThat(workspaceService.readFile(alice.getId(), ws, "notes.txt"))
                .isEqualTo("contenu mis à jour");
        // L'échange est persisté (user + assistant).
        org.assertj.core.api.Assertions.assertThat(
                atelierMessageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(ws, alice.getId())).hasSize(2);
    }

    @Test
    void historyReturnsPastMessages() throws Exception {
        UUID ws = createWorkspace(alice, "a.txt", "x");
        stub.enqueueFinal("Bonjour.");
        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"salut\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)))
                .andExpect(jsonPath("$[0].role", is("USER")))
                .andExpect(jsonPath("$[1].role", is("ASSISTANT")));
    }

    @Test
    void cannotChatOnAnotherUsersWorkspace() throws Exception {
        UUID ws = createWorkspace(alice, "a.txt", "x");
        stub.enqueueFinal("ne devrait pas être atteint");
        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"coucou\"}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------ POST /workspaces/{id}/chat/stream (SF-28-05)

    @Test
    void streamRelaysActionThenDoneAsSse() throws Exception {
        UUID ws = createWorkspace(alice, "notes.txt", "contenu initial");
        stub.enqueueToolCall("read_file", "path", "notes.txt");
        stub.enqueueFinal("J'ai lu notes.txt.");

        var result = mockMvc.perform(post("/api/workspaces/" + ws + "/chat/stream").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"lis notes.txt\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:action")   // étape d'action relayée
                .contains("read")            // type d'action
                .contains("notes.txt")       // chemin
                .contains("event:done")      // fin de flux
                .contains("J'ai lu notes.txt.");
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentType())
                .contains("text/event-stream");
    }

    @Test
    void streamOnAnotherUsersWorkspaceEmitsErrorInStreamNotHttp406() throws Exception {
        UUID ws = createWorkspace(alice, "a.txt", "x");
        stub.enqueueFinal("ne devrait pas être atteint");

        var result = mockMvc.perform(post("/api/workspaces/" + ws + "/chat/stream").contextPath("/api")
                        .header("Authorization", bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"coucou\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();

        // L'isolation est émise DANS le flux (jamais un 406/404 via l'@ExceptionHandler JSON).
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains("event:error")
                .contains("workspace_not_found");
        // Le fournisseur n'a jamais été sollicité (aucun tour joué).
        org.assertj.core.api.Assertions.assertThat(stub.lastRequest).isNull();
    }

    @Test
    void pathTraversalInToolCallIsRefusedWithoutTouchingOutside() throws Exception {
        UUID ws = createWorkspace(alice, "a.txt", "x");
        // L'agent tente d'écrire hors du workspace : l'outil renvoie une erreur, la boucle continue.
        stub.enqueueToolCall("write_file", "path", "../evil.txt", "content", "pwned");
        stub.enqueueFinal("Je ne peux pas écrire hors du projet.");

        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"écris ../evil.txt\"}"))
                .andExpect(status().isOk());

        // Aucune fuite : le fichier de traversée n'apparaît pas dans l'arborescence.
        org.assertj.core.api.Assertions.assertThat(workspaceService.tree(alice.getId(), ws))
                .noneMatch(p -> p.contains("evil"));
    }
}
