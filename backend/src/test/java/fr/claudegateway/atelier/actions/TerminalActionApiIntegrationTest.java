package fr.claudegateway.atelier.actions;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import fr.claudegateway.atelier.WorkspaceRepository;
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
 * Les actions d'un terminal, de bout en bout (F-151 / SF-151-01) : inscription, liste, fermeture
 * avec sa raison, annulation — et <b>le terminal d'Alice est introuvable pour Bob</b>.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TerminalActionApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private TerminalActionRepository actionRepository;
    @Autowired
    private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private String workspaceId;

    @BeforeEach
    void setUp() throws Exception {
        actionRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        aliceToken = tokenFor("alice-actions@ex.com");
        bobToken = tokenFor("bob-actions@ex.com");
        workspaceId = createWorkspace(aliceToken);
    }

    private String tokenFor(String email) {
        User user = userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        subscriptionRepository.save(Subscription.builder().userId(user.getId())
                .planCode(PlanCode.GOLD).status(SubscriptionStatus.ACTIVE).build());
        return jwtService.generateToken(user);
    }

    private String createWorkspace(String token) throws Exception {
        String body = mockMvc.perform(multipart("/api/workspaces").file(zip()).contextPath("/api")
                        .param("name", "Connecteur AGENOR")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static MockMultipartFile zip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("README.md"));
            zos.write("hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return new MockMultipartFile("file", "project.zip", "application/zip", out.toByteArray());
    }

    private String createAction(String token, String json) throws Exception {
        String body = mockMvc.perform(post("/api/workspaces/" + workspaceId + "/actions")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    @DisplayName("une action s'inscrit, se liste, se ferme — et sa raison reste lisible")
    void fullCycle() throws Exception {
        String actionId = createAction(aliceToken, """
                {"description":"Demander l'accès VPN à Karim",
                 "blocks":"le déploiement du connecteur",
                 "person":"Karim","kind":"MESSAGE"}""");

        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/actions").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].description", is("Demander l'accès VPN à Karim")))
                .andExpect(jsonPath("$[0].blocks", is("le déploiement du connecteur")))
                .andExpect(jsonPath("$[0].status", is("OPEN")));

        mockMvc.perform(post("/api/workspaces/" + workspaceId + "/actions/" + actionId + "/close")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Karim a ouvert l'accès ce matin.\"}")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DONE")))
                .andExpect(jsonPath("$.closedReason", is("Karim a ouvert l'accès ce matin.")));

        // Le menu ne montre plus que ce qui reste à faire ; l'historique est demandable.
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/actions").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/actions?openOnly=false")
                        .contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("annuler est un droit ; « Rétablir » revient en arrière")
    void cancelAndReopen() throws Exception {
        String actionId = createAction(aliceToken, """
                {"description":"Relancer le support réseau"}""");

        mockMvc.perform(post("/api/workspaces/" + workspaceId + "/actions/" + actionId + "/cancel")
                        .contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")));

        mockMvc.perform(post("/api/workspaces/" + workspaceId + "/actions/" + actionId + "/reopen")
                        .contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("OPEN")));
    }

    @Test
    @DisplayName("une action sans énoncé est refusée, sans stacktrace")
    void refusesAnEmptyDescription() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + workspaceId + "/actions").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"   \"}")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("terminal_action_invalid")));
    }

    @Test
    @DisplayName("ISOLATION — le terminal d'Alice est INTROUVABLE pour Bob, en lecture comme en écriture")
    void bobSeesNothingOfAlice() throws Exception {
        String actionId = createAction(aliceToken, """
                {"description":"Obtenir la validation du RSSI"}""");

        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/actions").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/workspaces/" + workspaceId + "/actions").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"m'inviter chez Alice\"}")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/workspaces/" + workspaceId + "/actions/" + actionId + "/close")
                        .contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("sans jeton, rien — la route est fermée")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/actions").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
