package fr.claudegateway.runner.host;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>Désigner un dossier au lieu de le taper</b> (F-71 / SF-71-02), de bout en bout.
 *
 * <p>Le point dur n'est pas le cas où la machine répond — aucun runner n'est connecté dans un test
 * d'intégration — mais celui où elle ne répond <b>pas</b> : c'est précisément le cas que le PO a
 * demandé de traiter. La gateway doit le <b>dire</b>, et jamais rendre une liste vide qui ferait
 * croire à une racine sans sous-dossier.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HostFoldersApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerAuditRepository auditRepository;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private String plainToken;
    private RunnerHost aliceHost;
    private RunnerHost bobHost;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-folders@example.com", UserRole.ADMIN);
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), "Poste CAGIP");

        User bob = seedUser("bob-folders@example.com", UserRole.ADMIN);
        bobToken = jwtService.generateToken(bob);
        bobHost = seedHost(bob.getId(), "Poste de Bob");

        plainToken = jwtService.generateToken(seedUser("plain-folders@example.com", UserRole.USER));
    }

    private User seedUser(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private RunnerHost seedHost(UUID userId, String name) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name(name).rootName("dev")
                .os("linux").shell("posix").elevated(false).build());
    }

    private String url(RunnerHost host) {
        return "/api/runner-hosts/" + host.getId() + "/folders";
    }

    @Test
    void anOfflineRunnerIsSaidPlainlyAndNeverReturnsAnEmptyList() throws Exception {
        // Décision du PO : on ne peut pas lister sans machine — alors on le dit, plutôt que d'offrir
        // un champ vide. Le 409 dit « état réparable », pas « panne de la gateway ».
        mockMvc.perform(get(url(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("runner_browse_unavailable"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("n'est pas connecté")));
    }

    @Test
    void anImpossiblePathIsRefusedBeforeReachingTheMachine() throws Exception {
        mockMvc.perform(get(url(aliceHost)).param("path", "../etc").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_project_path"));
    }

    @Test
    void anAbsolutePathIsRefused() throws Exception {
        mockMvc.perform(get(url(aliceHost)).param("path", "/etc").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_project_path"));
    }

    @Test
    void anotherAccountsMachineIsNeverBrowsed() throws Exception {
        // Isolation : le refus est indifférencié — il ne dit pas si le poste existe.
        mockMvc.perform(get(url(bobHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownHostIsNotFound() throws Exception {
        mockMvc.perform(get("/api/runner-hosts/" + UUID.randomUUID() + "/folders")
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void browsingIsClosedWithoutAtelierAccess() throws Exception {
        mockMvc.perform(get(url(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("atelier_forbidden"));
    }

    @Test
    void browsingIsClosedWithoutJwt() throws Exception {
        mockMvc.perform(get(url(aliceHost)).contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bobKeepsBrowsingHisOwnMachine() throws Exception {
        // Le pendant du test d'isolation : Bob n'est pas bloqué, il est simplement seul chez lui.
        mockMvc.perform(get(url(bobHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("runner_browse_unavailable"));
    }
}
