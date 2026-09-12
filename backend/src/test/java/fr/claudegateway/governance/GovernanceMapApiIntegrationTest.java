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
 * F-92 / SF-92-02 — les deux routes de lecture de la carte, de bout en bout.
 *
 * <p>Ce que ce test protège avant tout : <b>rien ne traverse un compte</b>. Le poste d'Alice rend
 * 404 à Bob — « introuvable », jamais « interdit », qui apprendrait qu'un poste existe sous cet
 * identifiant — et <b>aucune lecture ne part</b> vers sa machine.</p>
 *
 * <p>Il protège aussi le fait que ces routes ne sont <b>pas un explorateur de fichiers</b> : un
 * chemin hors carte rend 404, quel qu'il soit.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GovernanceMapApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private GovernancePackageRepository packages;
    @Autowired private GovernancePackageFileRepository packageFiles;
    @Autowired private GovernanceSelectionRepository selections;
    @Autowired private GovernanceActivationRepository activations;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private UUID aliceHost;

    @BeforeEach
    void setUp() {
        activations.deleteAll();
        selections.deleteAll();
        packageFiles.deleteAll();
        packages.deleteAll();
        workspaceRepository.deleteAll();
        hosts.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-map@example.com");
        aliceToken = jwtService.generateToken(alice);
        aliceHost = hosts.save(RunnerHost.builder().userId(alice.getId()).name("FREE").build())
                .getId();
        // Un projet sans machine, pour que le poste « Hébergé » existe.
        workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("archive").build());

        bobToken = jwtService.generateToken(seedUser("bob-map@example.com"));
    }

    private User seedUser(String email) {
        // ADMIN : l'accès Atelier (F-40) est accordé aux admins sans abonnement.
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private String mapPath(UUID hostId) {
        return "/api/governance/hosts/" + hostId + "/map";
    }

    @Test
    @DisplayName("sans jeton, la carte n'est pas lisible")
    void anonymousIsRefused() throws Exception {
        mockMvc.perform(get(mapPath(aliceHost)).contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("le poste d'un autre compte est INTROUVABLE, jamais « interdit »")
    void anotherAccountsHostIsNotFound() throws Exception {
        mockMvc.perform(get(mapPath(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(mapPath(aliceHost) + "/file").param("path", "README.md")
                        .contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("un poste non gouverné rend le geste qui le gouverne, pas une carte vide")
    void anUngovernedHostNamesItsFix() throws Exception {
        mockMvc.perform(get(mapPath(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostName").value("FREE"))
                .andExpect(jsonPath("$.supported").value(true))
                .andExpect(jsonPath("$.governed").value(false))
                .andExpect(jsonPath("$.facts").value(0))
                // F-93 / SF-93-02 : rien à dire, donc RIEN n'est dit. Un « +0 » affiché chaque jour
                // apprendrait qu'on ne gagne rien.
                .andExpect(jsonPath("$.growth").doesNotExist())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Le savoir durable")));
    }

    @Test
    @DisplayName("le poste « Hébergé » n'est pas une machine : il n'aura jamais de carte")
    void theHostedHostHasNoMap() throws Exception {
        mockMvc.perform(get("/api/governance/hosts/hosted/map").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supported").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Connectez une machine")));
    }

    @Test
    @DisplayName("lire un fichier sans chemin est refusé ; un chemin hors carte est introuvable")
    void readingAFileRequiresAMapPath() throws Exception {
        mockMvc.perform(get(mapPath(aliceHost) + "/file").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(mapPath(aliceHost) + "/file").param("path", "secrets.env")
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }
}
