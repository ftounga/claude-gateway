package fr.claudegateway.atelier;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de l'Atelier {@code /api/workspaces} : cycle upload/lecture/écriture, sécurité
 * (zip-slip, path traversal), isolation {@code user_id} et authentification. Stockage en mémoire.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AtelierApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private JwtService jwtService;

    private User alice;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
        alice = userRepository.save(User.builder().email("alice@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
        User bob = userRepository.save(User.builder().email("bob@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private static MockMultipartFile zipFile(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return new MockMultipartFile("file", "project.zip", "application/zip", out.toByteArray());
    }

    private String createWorkspace(String token, Map<String, String> entries) throws Exception {
        String body = mockMvc.perform(multipart("/api/workspaces").file(zipFile(entries)).contextPath("/api")
                        .param("name", "Mon projet")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void createReadWriteCycle() throws Exception {
        String id = createWorkspace(aliceToken, Map.of("src/App.java", "class App {}", "README.md", "hello"));

        // Détail : arborescence contient les fichiers + CLAUDE.md initialisé.
        mockMvc.perform(get("/api/workspaces/" + id).contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Mon projet")))
                .andExpect(jsonPath("$.files", hasItem("src/App.java")))
                .andExpect(jsonPath("$.files", hasItem("CLAUDE.md")));

        // Liste.
        mockMvc.perform(get("/api/workspaces").contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)));

        // Lecture d'un fichier.
        mockMvc.perform(get("/api/workspaces/" + id + "/file").param("path", "src/App.java")
                        .contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", is("class App {}")));

        // Écriture puis relecture.
        mockMvc.perform(put("/api/workspaces/" + id + "/file").param("path", "src/App.java")
                        .contextPath("/api").header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"class App { int x; }\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/workspaces/" + id + "/file").param("path", "src/App.java")
                        .contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(jsonPath("$.content", is("class App { int x; }")));
    }

    @Test
    void initialisesClaudeMdWhenAbsent() throws Exception {
        String id = createWorkspace(aliceToken, Map.of("a.txt", "x"));
        mockMvc.perform(get("/api/workspaces/" + id + "/file").param("path", "CLAUDE.md")
                        .contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", notNullValue()));
    }

    @Test
    void ignoresZipSlipEntries() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("../evil.txt", "pwned");
        entries.put("ok.txt", "safe");
        String id = createWorkspace(aliceToken, entries);

        // ok.txt + CLAUDE.md uniquement ; l'entrée « ../evil.txt » a été ignorée (zip-slip).
        mockMvc.perform(get("/api/workspaces/" + id).contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files.length()", is(2)))
                .andExpect(jsonPath("$.files", hasItem("ok.txt")))
                .andExpect(jsonPath("$.files", hasItem("CLAUDE.md")))
                .andExpect(jsonPath("$.files", org.hamcrest.Matchers.not(hasItem("evil.txt"))));
    }

    @Test
    void rejectsPathTraversalOnRead() throws Exception {
        String id = createWorkspace(aliceToken, Map.of("a.txt", "x"));
        mockMvc.perform(get("/api/workspaces/" + id + "/file").param("path", "../../etc/passwd")
                        .contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_file_path")));
    }

    @Test
    void cannotAccessAnotherUsersWorkspace() throws Exception {
        String bobWs = createWorkspace(bobToken, Map.of("secret.txt", "de Bob"));

        mockMvc.perform(get("/api/workspaces/" + bobWs).contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("not_found")));
        mockMvc.perform(get("/api/workspaces/" + bobWs + "/file").param("path", "secret.txt")
                        .contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesWorkspace() throws Exception {
        String id = createWorkspace(aliceToken, Map.of("a.txt", "x"));
        mockMvc.perform(delete("/api/workspaces/" + id).contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/workspaces/" + id).contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/workspaces").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
