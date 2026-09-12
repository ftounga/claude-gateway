package fr.claudegateway.runner.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
 * <b>Ajouter un projet à un poste déjà connecté</b> (F-72 / SF-72-01), de bout en bout.
 *
 * <p>Le geste que le parcours d'avant rendait impossible : on part du <b>poste</b>, on désigne un
 * dossier, et le projet existe — sans réappairer, et <b>sans redemander le nom du client</b>.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HostProjectsApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerAuditRepository auditRepository;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String plainToken;
    private UUID aliceId;
    private RunnerHost aliceHost;
    private RunnerHost bobHost;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-hostprojects@example.com", UserRole.ADMIN);
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), "EDENRED");

        User bob = seedUser("bob-hostprojects@example.com", UserRole.ADMIN);
        bobHost = seedHost(bob.getId(), "Poste de Bob");

        plainToken = jwtService.generateToken(
                seedUser("plain-hostprojects@example.com", UserRole.USER));
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
        return "/api/runner-hosts/" + host.getId() + "/projects";
    }

    @Test
    void aFolderBecomesAProjectNamedAfterIt() throws Exception {
        mockMvc.perform(post(url(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"clients/EDENRED\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("EDENRED"))
                .andExpect(jsonPath("$.hostId").value(aliceHost.getId().toString()))
                .andExpect(jsonPath("$.projectPath").value("clients/EDENRED"))
                .andExpect(jsonPath("$.executionTarget").value("RUNNER"));

        assertThat(workspaceRepository.findByUserIdAndHostIdAndHostTerminalFalse(aliceId, aliceHost.getId())).hasSize(1);
    }

    @Test
    void anEmptyBodyOpensTheRootUnderTheHostName() throws Exception {
        // Un poste peut n'héberger qu'un projet : la racine est un choix, pas un oubli. Et le nom
        // du client, déjà donné à la connexion du poste, n'est pas redemandé.
        mockMvc.perform(post(url(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("EDENRED"))
                .andExpect(jsonPath("$.projectPath").value(""));
    }

    @Test
    void openingTheSameFolderTwiceIsRefusedAndCreatesNothing() throws Exception {
        mockMvc.perform(post(url(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"clients/EDENRED\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(url(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"clients/EDENRED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("host_project_exists"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("EDENRED")));

        // Le défaut vécu par le PO : deux entités du même nom. Il n'y en a toujours qu'une.
        assertThat(workspaceRepository.findByUserIdAndHostIdAndHostTerminalFalse(aliceId, aliceHost.getId())).hasSize(1);
    }

    @Test
    void anEscapingPathIsRefusedAndCreatesNothing() throws Exception {
        mockMvc.perform(post(url(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"../etc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_project_path"));

        assertThat(workspaceRepository.findByUserIdAndHostIdAndHostTerminalFalse(aliceId, aliceHost.getId())).isEmpty();
    }

    @Test
    void anotherAccountsHostNeverReceivesAProject() throws Exception {
        // Isolation : le refus est indifférencié — il ne dit même pas si le poste existe.
        mockMvc.perform(post(url(bobHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"dev\"}"))
                .andExpect(status().isNotFound());

        assertThat(workspaceRepository.findAll()).isEmpty();
    }

    @Test
    void anUnknownHostIsNotFound() throws Exception {
        mockMvc.perform(post("/api/runner-hosts/" + UUID.randomUUID() + "/projects")
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"dev\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void openingIsClosedWithoutAtelierAccess() throws Exception {
        mockMvc.perform(post(url(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + plainToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"dev\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("atelier_forbidden"));
    }

    @Test
    void openingIsClosedWithoutJwt() throws Exception {
        mockMvc.perform(post(url(aliceHost)).contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"dev\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void severalFoldersBecomeSeveralProjectsWithoutAnyPairing() throws Exception {
        // Tout le bénéfice de F-48, enfin atteignable depuis l'écran : autant de projets qu'on veut
        // sous un poste appairé UNE fois.
        for (String folder : new String[] {"clients/EDENRED", "clients/CAGIP", "perso/labo"}) {
            mockMvc.perform(post(url(aliceHost)).contextPath("/api")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"path\":\"" + folder + "\"}"))
                    .andExpect(status().isCreated());
        }

        assertThat(workspaceRepository.findByUserIdAndHostIdAndHostTerminalFalse(aliceId, aliceHost.getId())).hasSize(3);
    }
}
