package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.agent.StubAiAgentProvider;
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
 * F-40 / SF-40-01 — <b>préoccupation transversale « Plans / limites »</b>, vérifiée endpoint par
 * endpoint.
 *
 * <p>Cinq contrôleurs appellent le gating de l'Atelier. Le passage d'un test de <b>plan</b> à un test
 * de <b>droit</b> change leur comportement pour un abonné Solo, et pour lui seul. Chacun est donc
 * exercé deux fois : <b>sans</b> option (refus attendu, exactement comme avant F-40) puis <b>avec</b>
 * option (l'accès passe, et ce qui refuse ensuite n'est plus la porte de l'Atelier).</p>
 *
 * <p>Les deux endpoints <b>SSE</b> sont traités à part : ils ne lèvent jamais d'exception synchrone
 * (un 403 y produirait un 406), leur refus voyage <i>dans le flux</i> sous la forme d'un événement
 * {@code error: forbidden}. Un 403 sur ces deux-là serait un bug, pas une réussite.</p>
 */
@TestPropertySource(properties = "app.atelier.storage-execution=true")
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AtelierOptionAccessApiIntegrationTest {

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
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private AtelierMessageRepository atelierMessageRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private JwtService jwtService;
    @Autowired private StubAiAgentProvider stub;

    /** Abonnée Solo : c'est elle qui gagne (ou non) le droit selon l'option. */
    private User alice;
    private String aliceToken;
    /** Abonné Solo lui aussi, mais jamais optionnaire : le témoin de l'isolation. */
    private User bob;
    private String bobToken;

    private UUID aliceWorkspace;
    private UUID bobWorkspace;

    @BeforeEach
    void setUp() throws Exception {
        atelierMessageRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        stub.reset();

        alice = userRepository.save(User.builder().email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
        bob = userRepository.save(User.builder().email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);

        // Les projets sont créés AVANT le gating : la porte de l'Atelier garde les endpoints, pas
        // le service. Les créer en direct isole ce que ce test mesure — le droit, rien d'autre.
        aliceWorkspace = createWorkspace(alice);
        bobWorkspace = createWorkspace(bob);
    }

    // ------------------------------------------------------------------ outillage

    private UUID createWorkspace(User user) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry("notes.txt"));
            zip.write("contenu".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return workspaceService.create(user.getId(), "projet", baos.toByteArray()).workspace().getId();
    }

    private void subscribe(User user, PlanCode plan, SubscriptionStatus status,
            SubscriptionStatus optionStatus) {
        Subscription existing = subscriptionRepository.findByUserId(user.getId())
                .orElseGet(() -> Subscription.builder().userId(user.getId()).build());
        existing.setPlanCode(plan);
        existing.setStatus(status);
        existing.setAtelierOptionStatus(optionStatus);
        subscriptionRepository.save(existing);
    }

    private void soloWithoutOption(User user) {
        subscribe(user, PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);
    }

    private void soloWithOption(User user) {
        subscribe(user, PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    /** Corps SSE lu après démarrage de l'asynchrone (l'exécuteur du test est synchrone). */
    private String sse(String path, String token, String body) throws Exception {
        return mockMvc.perform(post(path).contextPath("/api")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    // ------------------------------------------------------- 1. AtelierController

    @Test
    @DisplayName("AtelierController — GET /workspaces : refusé en Solo, ouvert avec l'option")
    void atelierControllerFollowsTheEntitlement() throws Exception {
        soloWithoutOption(alice);
        mockMvc.perform(get("/api/workspaces").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("atelier_forbidden")));

        soloWithOption(alice);
        mockMvc.perform(get("/api/workspaces").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)));
    }

    // --------------------------------------------------- 2. AtelierChatController

    @Test
    @DisplayName("AtelierChatController — POST /chat : refusé en Solo, ouvert avec l'option")
    void atelierChatControllerFollowsTheEntitlement() throws Exception {
        soloWithoutOption(alice);
        stub.enqueueFinal("ne devrait pas être atteint");
        mockMvc.perform(post("/api/workspaces/" + aliceWorkspace + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"salut\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("atelier_forbidden")));
        assertThat(stub.lastRequest).as("le fournisseur n'est pas sollicité derrière une porte fermée")
                .isNull();

        soloWithOption(alice);
        mockMvc.perform(post("/api/workspaces/" + aliceWorkspace + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"salut\"}"))
                .andExpect(status().isOk());
        assertThat(stub.lastRequest).as("l'option ouvre réellement la boucle").isNotNull();
    }

    @Test
    @DisplayName("AtelierChatController — POST /chat/stream : le refus voyage dans le flux, jamais en 403")
    void atelierChatStreamCarriesTheRefusalInsideTheStream() throws Exception {
        soloWithoutOption(alice);
        String denied = sse("/api/workspaces/" + aliceWorkspace + "/chat/stream", aliceToken,
                "{\"message\":\"salut\"}");
        assertThat(denied).contains("forbidden");

        soloWithOption(alice);
        stub.enqueueFinal("bonjour");
        String allowed = sse("/api/workspaces/" + aliceWorkspace + "/chat/stream", aliceToken,
                "{\"message\":\"salut\"}");
        assertThat(allowed).doesNotContain("forbidden");
    }

    // -------------------------------------------------- 3. AtelierAgentController

    @Test
    @DisplayName("AtelierAgentController — POST /agent/stream : refus dans le flux, levé par l'option")
    void atelierAgentStreamCarriesTheRefusalInsideTheStream() throws Exception {
        soloWithoutOption(alice);
        String denied = sse("/api/workspaces/" + aliceWorkspace + "/agent/stream", aliceToken,
                "{\"message\":\"salut\"}");
        assertThat(denied).contains("forbidden");

        // Avec l'option, la porte de l'Atelier ne refuse plus : ce qui refuse ensuite est le flag
        // Managed Agents, éteint par défaut. Le refus a changé de nature, c'est ce qu'on mesure.
        soloWithOption(alice);
        String allowed = sse("/api/workspaces/" + aliceWorkspace + "/agent/stream", aliceToken,
                "{\"message\":\"salut\"}");
        assertThat(allowed).doesNotContain("forbidden");
    }

    // ------------------------------------------------ 4. GitWorkspaceController

    @Test
    @DisplayName("GitWorkspaceController — POST /workspaces/git : refusé en Solo, ouvert avec l'option")
    void gitWorkspaceControllerFollowsTheEntitlement() throws Exception {
        String body = "{\"repoUrl\":\"https://github.com/acme/demo\"}";

        soloWithoutOption(alice);
        mockMvc.perform(post("/api/workspaces/git").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("atelier_forbidden")));

        // Avec l'option, la porte de l'Atelier est franchie : le refus suivant est celui du jeton
        // GitHub absent (400), c'est-à-dire un refus métier et non plus un refus de facturation.
        soloWithOption(alice);
        mockMvc.perform(post("/api/workspaces/git").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("git_token_missing")));
    }

    // -------------------------------------------- 5. RunnerManagementController

    @Test
    @DisplayName("RunnerManagementController — GET /runner/status : refusé en Solo, ouvert avec l'option")
    void runnerManagementControllerFollowsTheEntitlement() throws Exception {
        String path = "/api/workspaces/" + aliceWorkspace + "/runner/status";

        soloWithoutOption(alice);
        mockMvc.perform(get(path).contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("atelier_forbidden")));

        soloWithOption(alice);
        mockMvc.perform(get(path).contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------- non-régression Gold

    @Test
    @DisplayName("Non-régression Gold — l'abonné Gold garde l'accès aux cinq contrôleurs, sans option")
    void goldKeepsAccessEverywhereWithoutAnyOption() throws Exception {
        subscribe(alice, PlanCode.GOLD, SubscriptionStatus.ACTIVE, null);

        mockMvc.perform(get("/api/workspaces").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/workspaces/" + aliceWorkspace + "/runner/status").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/workspaces/git").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://github.com/acme/demo\"}"))
                .andExpect(jsonPath("$.error", is("git_token_missing")));

        stub.enqueueFinal("bonjour");
        mockMvc.perform(post("/api/workspaces/" + aliceWorkspace + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"salut\"}"))
                .andExpect(status().isOk());
        assertThat(sse("/api/workspaces/" + aliceWorkspace + "/agent/stream", aliceToken,
                "{\"message\":\"salut\"}")).doesNotContain("forbidden");
    }

    @Test
    @DisplayName("Non-régression Gold — Gold résilié reste refusé, l'option ne le rattrape pas")
    void canceledGoldStaysDenied() throws Exception {
        subscribe(alice, PlanCode.GOLD, SubscriptionStatus.CANCELED, null);

        mockMvc.perform(get("/api/workspaces").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("atelier_forbidden")));
    }

    // --------------------------------------------------------------- quota intact

    @Test
    @DisplayName("Le quota est inchangé par l'option : elle ouvre un droit, pas un jeton")
    void optionDoesNotChangeTheTokenQuota() throws Exception {
        soloWithoutOption(alice);
        String without = mockMvc.perform(get("/api/usage").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long quotaWithout = com.jayway.jsonpath.JsonPath.parse(without).read("$.quotaTokens", Long.class);

        soloWithOption(alice);
        mockMvc.perform(get("/api/usage").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotaTokens", is((int) quotaWithout)));
    }

    // ------------------------------------------------------- isolation utilisateur

    @Test
    @DisplayName("Isolation — l'option d'Alice n'ouvre aucun droit à Bob")
    void aliceOptionDoesNotLeakToBob() throws Exception {
        soloWithOption(alice);
        soloWithoutOption(bob);

        mockMvc.perform(get("/api/workspaces").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/workspaces").contextPath("/api")
                        .header("Authorization", bearer(bobToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("atelier_forbidden")));

        // Et Bob, même optionnaire, ne voit toujours pas le projet d'Alice : le droit ouvre la
        // porte, il n'élargit jamais le filtre user_id.
        soloWithOption(bob);
        mockMvc.perform(get("/api/workspaces/" + aliceWorkspace + "/runner/status").contextPath("/api")
                        .header("Authorization", bearer(bobToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/workspaces/" + bobWorkspace + "/runner/status").contextPath("/api")
                        .header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk());
    }
}
