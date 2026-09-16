package fr.claudegateway.activity.cra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fr.claudegateway.activity.CraEntry;
import fr.claudegateway.activity.CraEntryRepository;
import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Le CRA par message, de bout en bout (F-124 / SF-124-03), provider et quota mockés. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CraApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private CraEntryRepository craRepository;
    @Autowired private JwtService jwtService;

    @MockitoBean private AIProvider aiProvider;
    @MockitoBean private QuotaService quotaService;

    private User alice;
    private String aliceToken;
    private RunnerHost aliceFree;
    private RunnerHost aliceKg;
    private String noraToken;

    @BeforeEach
    void setUp() {
        craRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        alice = seedUser("alice-cra@example.com", UserRole.ADMIN);
        aliceToken = jwtService.generateToken(alice);
        aliceFree = seedHost(alice.getId(), "Free");
        aliceKg = seedHost(alice.getId(), "KG");

        // Bob possède AUSSI un poste « Free » : le message d'Alice ne doit jamais l'atteindre.
        // Il a en plus un poste au nom unique, qui ne doit JAMAIS apparaître dans la consigne d'Alice.
        User bob = seedUser("bob-cra@example.com", UserRole.ADMIN);
        seedHost(bob.getId(), "Free");
        seedHost(bob.getId(), "BobSecretPoste");

        User nora = seedUser("nora-cra@example.com", UserRole.USER);
        noraToken = jwtService.generateToken(nora);
    }

    private User seedUser(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private RunnerHost seedHost(UUID userId, String name) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name(name).rootName("dev")
                .os("linux").shell("posix").elevated(false).build());
    }

    private void modelReturns(String json) {
        when(aiProvider.complete(any(ChatCompletionRequest.class)))
                .thenReturn(new ChatCompletionResult(json, "fast", 10, 5));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    @Test
    void writesTwoRecognizedClients() throws Exception {
        modelReturns("[{\"client\":\"Free\",\"days\":20,\"month\":\"2025-09\"},"
                + "{\"client\":\"KG\",\"days\":13,\"month\":\"2025-09\"}]");

        mockMvc.perform(as(post("/api/activity/cra"), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Free 20j, KG 13j en septembre\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(2))
                .andExpect(jsonPath("$.unknown").value(0))
                .andExpect(jsonPath("$.lines.length()").value(2));

        assertThat(craRepository.findByUserIdAndHostIdAndYearMonth(alice.getId(), aliceFree.getId(), "2025-09")
                .orElseThrow().getDays()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(craRepository.findByUserIdAndHostIdAndYearMonth(alice.getId(), aliceKg.getId(), "2025-09")
                .orElseThrow().getDays()).isEqualByComparingTo(new BigDecimal("13"));
    }

    @Test
    void overwritesAMonth() throws Exception {
        craRepository.save(CraEntry.builder().userId(alice.getId()).hostId(aliceFree.getId())
                .yearMonth("2025-09").days(new BigDecimal("20")).build());
        modelReturns("[{\"client\":\"Free\",\"days\":18,\"month\":\"2025-09\"}]");

        mockMvc.perform(as(post("/api/activity/cra"), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"finalement Free 18j en septembre\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(1));

        assertThat(craRepository.findByUserId(alice.getId())).hasSize(1); // pas de doublon
        assertThat(craRepository.findByUserIdAndHostIdAndYearMonth(alice.getId(), aliceFree.getId(), "2025-09")
                .orElseThrow().getDays()).isEqualByComparingTo(new BigDecimal("18"));
    }

    @Test
    void unknownClientIsAskedAndNothingIsWritten() throws Exception {
        modelReturns("[{\"client\":\"Acme\",\"days\":5,\"month\":\"2025-09\"}]");

        mockMvc.perform(as(post("/api/activity/cra"), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Acme 5j\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unknown").value(1))
                .andExpect(jsonPath("$.written").value(0))
                .andExpect(jsonPath("$.lines[0].status").value("UNKNOWN_HOST"))
                .andExpect(jsonPath("$.lines[0].cited").value("Acme"));
        assertThat(craRepository.findAll()).isEmpty();
    }

    @Test
    void aMessageNeverWritesOnAnotherUsersHost() throws Exception {
        // Alice cite « Free » : seul SON poste Free est touché ; celui de Bob (même nom) reste vierge.
        modelReturns("[{\"client\":\"Free\",\"days\":10,\"month\":\"2025-09\"}]");
        mockMvc.perform(as(post("/api/activity/cra"), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Free 10j\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(1));

        assertThat(craRepository.findAll()).hasSize(1);
        assertThat(craRepository.findAll().get(0).getUserId()).isEqualTo(alice.getId());
        assertThat(craRepository.findAll().get(0).getHostId()).isEqualTo(aliceFree.getId());
    }

    @Test
    void aRangeIsConvertedToBusinessDays_serverSide() throws Exception {
        // « du 10 à la fin du mois » chez Free en août 2025 → 14 jours ouvrés (15/08 férié), persistés.
        modelReturns("[{\"client\":\"Free\",\"range\":{\"fromDay\":10},\"month\":\"2025-08\"}]");

        mockMvc.perform(as(post("/api/activity/cra"), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"du 10 à la fin du mois chez Free\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(1))
                .andExpect(jsonPath("$.lines[0].period").value("du 10 a la fin du mois"))
                .andExpect(jsonPath("$.lines[0].days").value(14));

        assertThat(craRepository.findByUserIdAndHostIdAndYearMonth(alice.getId(), aliceFree.getId(), "2025-08")
                .orElseThrow().getDays()).isEqualByComparingTo(new BigDecimal("14"));
    }

    @Test
    void ownHostsAreGivenToTheModel_neverAnotherUsersHost() throws Exception {
        org.mockito.ArgumentCaptor<ChatCompletionRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ChatCompletionRequest.class);
        modelReturns("[]");

        mockMvc.perform(as(post("/api/activity/cra"), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"chez tous mes clients tout le mois\"}"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(aiProvider).complete(captor.capture());
        String system = captor.getValue().system();
        assertThat(system).contains("Free").contains("KG");
        assertThat(system).doesNotContain("BobSecretPoste"); // isolation : jamais le poste d'un autre
    }

    @Test
    void emptyMessageIsRejected() throws Exception {
        mockMvc.perform(as(post("/api/activity/cra"), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthenticationAndForgeRight() throws Exception {
        mockMvc.perform(post("/api/activity/cra").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Free 10j\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(as(post("/api/activity/cra"), noraToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Free 10j\"}"))
                .andExpect(status().isForbidden());
    }
}
