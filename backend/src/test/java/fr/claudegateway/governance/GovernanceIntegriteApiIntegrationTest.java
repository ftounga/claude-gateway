package fr.claudegateway.governance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-95 / SF-95-03 — la route qui rend <b>l'intégrité d'un poste</b>.
 *
 * <p>Ce qu'elle protège avant tout : <b>rien ne traverse un compte</b>. Le poste d'Alice rend 404 à
 * Bob — « introuvable », jamais « interdit », qui apprendrait qu'un poste existe sous cet
 * identifiant.</p>
 *
 * <p>Et la distinction qui porte toute la feature : un poste dont on n'a <b>rien lu</b> rend
 * {@code inspected: false} avec deux listes vides — ce n'est <b>pas</b> « tout va bien ».</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GovernanceIntegriteApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private UUID aliceHost;

    @BeforeEach
    void setUp() {
        workspaceRepository.deleteAll();
        hosts.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-integrite@example.com");
        aliceToken = jwtService.generateToken(alice);
        aliceHost = hosts.save(RunnerHost.builder().userId(alice.getId()).name("FREE").build())
                .getId();
        workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("archive").build());

        bobToken = jwtService.generateToken(seedUser("bob-integrite@example.com"));
    }

    private User seedUser(String email) {
        // ADMIN : l'accès Atelier (F-40) est accordé aux admins sans abonnement.
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private String path(String hostRef) {
        return "/api/governance/hosts/" + hostRef + "/integrite";
    }

    @Test
    @DisplayName("sans jeton, l'intégrité n'est pas lisible")
    void anonymousIsRefused() throws Exception {
        mockMvc.perform(get(path(aliceHost.toString())).contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("le poste d'un autre compte est INTROUVABLE, jamais « interdit »")
    void anotherAccountsHostIsNotFound() throws Exception {
        mockMvc.perform(get(path(aliceHost.toString())).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("un poste inconnu est introuvable")
    void anUnknownHostIsNotFound() throws Exception {
        mockMvc.perform(get(path(UUID.randomUUID().toString())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("un poste non gouverné n'est pas inspecté — et ce n'est pas « tout va bien »")
    void anUngovernedHostIsNotInspected() throws Exception {
        mockMvc.perform(get(path(aliceHost.toString())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inspected").value(false))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    @DisplayName("le poste « Hébergé » n'a pas de racine : rien à inspecter")
    void theHostedHostHasNoRoot() throws Exception {
        mockMvc.perform(get(path("hosted")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inspected").value(false))
                .andExpect(jsonPath("$.hostId").doesNotExist());
    }
}
