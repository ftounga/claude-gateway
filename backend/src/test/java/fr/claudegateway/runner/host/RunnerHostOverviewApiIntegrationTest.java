package fr.claudegateway.runner.host;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.audit.RunnerAudit;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * La vue d'ensemble des postes, de bout en bout (F-49 / SF-49-01) : ce que rend
 * {@code GET /runner-hosts/overview}, et surtout ce qu'il ne rend <b>jamais</b> — la machine d'un
 * autre compte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerHostOverviewApiIntegrationTest {

    private static final String URL = "/api/runner-hosts/overview";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerAuditRepository auditRepository;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private String plainToken;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-overview@example.com", UserRole.ADMIN);
        aliceToken = jwtService.generateToken(alice);
        RunnerHost aliceHost = seedHost(alice.getId(), "Poste CAGIP", OffsetDateTime.now());
        Workspace aliceProject = seedWorkspace(alice.getId(), aliceHost.getId(), "web", "web");
        seedAudit(alice.getId(), aliceHost.getId(), aliceProject.getId(), "bash");

        User bob = seedUser("bob-overview@example.com", UserRole.ADMIN);
        bobToken = jwtService.generateToken(bob);
        RunnerHost bobHost = seedHost(bob.getId(), "Poste de Bob", OffsetDateTime.now());
        Workspace bobProject = seedWorkspace(bob.getId(), bobHost.getId(), "secret", "secret");
        seedAudit(bob.getId(), bobHost.getId(), bobProject.getId(), "read");

        User plain = seedUser("plain-overview@example.com", UserRole.USER);
        plainToken = jwtService.generateToken(plain);
    }

    private User seedUser(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private RunnerHost seedHost(UUID userId, String name, OffsetDateTime lastSeenAt) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name(name).rootName("dev")
                .os("linux").shell("posix").elevated(false).lastSeenAt(lastSeenAt).build());
    }

    private Workspace seedWorkspace(UUID userId, UUID hostId, String name, String path) {
        return workspaceRepository.save(Workspace.builder().userId(userId).name(name)
                .hostId(hostId).projectPath(path).build());
    }

    private void seedAudit(UUID userId, UUID hostId, UUID workspaceId, String tool) {
        auditRepository.save(RunnerAudit.builder().userId(userId).hostId(hostId)
                .workspaceId(workspaceId).callId(UUID.randomUUID().toString()).tool(tool)
                .target("cible").outcome("OK").createdAt(OffsetDateTime.now()).build());
    }

    @Test
    void ownerSeesHisMachineItsProjectAndItsActivity() throws Exception {
        mockMvc.perform(get(URL).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Poste CAGIP"))
                .andExpect(jsonPath("$[0].rootName").value("dev"))
                .andExpect(jsonPath("$[0].os").value("linux"))
                .andExpect(jsonPath("$[0].shell").value("posix"))
                .andExpect(jsonPath("$[0].elevated").value(false))
                .andExpect(jsonPath("$[0].connected").value(false))
                .andExpect(jsonPath("$[0].activeProjects").value(1))
                .andExpect(jsonPath("$[0].projects.length()").value(1))
                .andExpect(jsonPath("$[0].projects[0].name").value("web"))
                .andExpect(jsonPath("$[0].projects[0].projectPath").value("web"))
                .andExpect(jsonPath("$[0].projects[0].executionTarget").value("SANDBOX"))
                .andExpect(jsonPath("$[0].projects[0].lastTool").value("bash"))
                .andExpect(jsonPath("$[0].projects[0].calls").value(1))
                .andExpect(jsonPath("$[0].projects[0].active").value(true));
    }

    @Test
    void theViewNeverLeaksAnotherAccountsMachine() throws Exception {
        // Isolation : Bob voit son poste et rien d'autre — ni la machine d'Alice, ni son projet.
        mockMvc.perform(get(URL).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Poste de Bob"))
                .andExpect(jsonPath("$[0].projects[0].name").value("secret"))
                .andExpect(jsonPath("$[0].projects[0].lastTool").value("read"));
    }

    @Test
    void aUserWithoutAnyHostGetsAnEmptyListNotAnError() throws Exception {
        User lonely = seedUser("lonely-overview@example.com", UserRole.ADMIN);
        mockMvc.perform(get(URL).contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(lonely)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theViewIsClosedWithoutAtelierAccess() throws Exception {
        mockMvc.perform(get(URL).contextPath("/api").header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("atelier_forbidden"));
    }

    @Test
    void theViewIsClosedWithoutJwt() throws Exception {
        mockMvc.perform(get(URL).contextPath("/api")).andExpect(status().isUnauthorized());
    }

    @Test
    void overviewIsNotSwallowedByTheHostIdRoute() throws Exception {
        // `/overview` n'est pas un UUID : si la route variable le captait, on obtiendrait un 400.
        mockMvc.perform(get(URL).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
    }

    // --------------------------------------------- le poste « Hébergé » (F-71 / SF-71-01)

    @Test
    void aProjectWithoutAMachineAppearsUnderTheHostedHost() throws Exception {
        // Un dépôt GitHub n'a pas de poste : il se range sous « Hébergé », EN DERNIER, avec un
        // identifiant NUL — il n'existe aucune ligne en base pour lui.
        User solo = seedUser("hosted-overview@example.com", UserRole.ADMIN);
        workspaceRepository.save(Workspace.builder().userId(solo.getId()).name("mon-depot")
                .hostId(null).projectPath(null).build());

        mockMvc.perform(get(URL).contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(solo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].virtual").value(true))
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].name").value("Hébergé"))
                .andExpect(jsonPath("$[0].connected").value(false))
                .andExpect(jsonPath("$[0].missionStatus").doesNotExist())
                .andExpect(jsonPath("$[0].projects.length()").value(1))
                .andExpect(jsonPath("$[0].projects[0].name").value("mon-depot"));
    }

    @Test
    void theHostedHostIsAbsentWhenEveryProjectHasAMachine() throws Exception {
        // Décision du PO : il n'apparaît que s'il contient quelque chose.
        mockMvc.perform(get(URL).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].virtual").value(false));
    }

    @Test
    void theHostedHostNeverGathersAnotherAccountsProject() throws Exception {
        // Isolation : le projet sans machine de Bob n'entre jamais dans l'« Hébergé » d'Alice.
        workspaceRepository.save(Workspace.builder()
                .userId(userRepository.findByEmail("bob-overview@example.com").orElseThrow().getId())
                .name("depot-secret").hostId(null).projectPath(null).build());

        mockMvc.perform(get(URL).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Poste CAGIP"));
    }
}
