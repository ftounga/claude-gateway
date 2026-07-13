package fr.claudegateway.atelier;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
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
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private JwtService jwtService;

    private User alice;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        // Gating SF-28-06 : l'Atelier est réservé à l'offre Gold. Alice et Bob sont abonnés Gold actif
        // pour exercer les endpoints ; un accès non-Gold est testé séparément (workspaceRequiresGoldAccess).
        alice = userRepository.save(User.builder().email("alice@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        provisionGold(alice);
        aliceToken = jwtService.generateToken(alice);
        User bob = userRepository.save(User.builder().email("bob@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        provisionGold(bob);
        bobToken = jwtService.generateToken(bob);
    }

    private void provisionGold(User user) {
        subscriptionRepository.save(Subscription.builder()
                .userId(user.getId())
                .planCode(PlanCode.GOLD)
                .status(SubscriptionStatus.ACTIVE)
                .build());
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
    void deleteFileRemovesItFromTree() throws Exception {
        String id = createWorkspace(aliceToken, Map.of("a.txt", "x", "keep.txt", "y"));

        mockMvc.perform(delete("/api/workspaces/" + id + "/file").param("path", "a.txt")
                        .contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/workspaces/" + id).contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files", org.hamcrest.Matchers.not(hasItem("a.txt"))))
                .andExpect(jsonPath("$.files", hasItem("keep.txt")));
    }

    @Test
    void deleteUnknownFileReturns404() throws Exception {
        String id = createWorkspace(aliceToken, Map.of("a.txt", "x"));
        mockMvc.perform(delete("/api/workspaces/" + id + "/file").param("path", "ghost.txt")
                        .contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void renameFileMovesContentAndReturnsTree() throws Exception {
        String id = createWorkspace(aliceToken, Map.of("a.txt", "hello"));

        mockMvc.perform(post("/api/workspaces/" + id + "/file/rename").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"a.txt\",\"to\":\"sub/b.txt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files", hasItem("sub/b.txt")))
                .andExpect(jsonPath("$.files", org.hamcrest.Matchers.not(hasItem("a.txt"))));

        mockMvc.perform(get("/api/workspaces/" + id + "/file").param("path", "sub/b.txt")
                        .contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", is("hello")));
    }

    @Test
    void renameFileRejectsInvalidDestination() throws Exception {
        String id = createWorkspace(aliceToken, Map.of("a.txt", "hello"));
        mockMvc.perform(post("/api/workspaces/" + id + "/file/rename").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"a.txt\",\"to\":\"../x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_file_path")));
    }

    @Test
    void exportReturnsZipAttachment() throws Exception {
        String id = createWorkspace(aliceToken, Map.of("a.txt", "x"));
        byte[] zip = mockMvc.perform(get("/api/workspaces/" + id + "/export").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Disposition",
                                org.hamcrest.Matchers.containsString("attachment; filename=")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentType("application/zip"))
                .andReturn().getResponse().getContentAsByteArray();

        // Round-trip : l'archive contient les chemins relatifs attendus.
        Map<String, String> unzipped = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                unzipped.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                zis.closeEntry();
            }
        }
        org.assertj.core.api.Assertions.assertThat(unzipped).containsKeys("a.txt", "CLAUDE.md");
    }

    @Test
    void cannotMutateAnotherUsersWorkspaceFiles() throws Exception {
        String bobWs = createWorkspace(bobToken, Map.of("secret.txt", "de Bob"));

        // Suppression cross-user : 404 (isolation user_id).
        mockMvc.perform(delete("/api/workspaces/" + bobWs + "/file").param("path", "secret.txt")
                        .contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNotFound());
        // Renommage cross-user : 404.
        mockMvc.perform(post("/api/workspaces/" + bobWs + "/file/rename").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"secret.txt\",\"to\":\"x.txt\"}"))
                .andExpect(status().isNotFound());
        // Export cross-user : 404.
        mockMvc.perform(get("/api/workspaces/" + bobWs + "/export").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/workspaces").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void workspaceCreationRequiresGoldAccess() throws Exception {
        // SF-28-06 : un utilisateur non-Gold non-admin (essai lazy) est refusé (403 atelier_forbidden).
        User charlie = userRepository.save(User.builder().email("charlie@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        String charlieToken = jwtService.generateToken(charlie);

        mockMvc.perform(multipart("/api/workspaces").file(zipFile(Map.of("a.txt", "x"))).contextPath("/api")
                        .param("name", "projet")
                        .header("Authorization", bearer(charlieToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("atelier_forbidden")));
    }
}
