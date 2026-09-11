package fr.claudegateway.terminals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Les <b>terminaux vivants</b>, de bout en bout (F-70 / SF-70-01).
 *
 * <p>Ce qui compte ici tient en trois questions : <b>combien vivent</b>, <b>que dit-on au
 * cinquième</b>, et <b>que voit-on du voisin</b>. La troisième est la plus importante : le registre
 * nomme des projets et des postes, et un plafond qui compterait les terminaux d'un autre serait à la
 * fois une fuite et un blocage.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LiveTerminalApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private LiveTerminalRepository liveTerminalRepository;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private UUID aliceId;
    private RunnerHost aliceHost;
    private Workspace aliceProject;
    private Workspace aliceOtherProject;
    private Workspace bobProject;

    @BeforeEach
    void setUp() {
        liveTerminalRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-terminals@example.com");
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(aliceId, "Poste CAGIP");
        aliceProject = seedWorkspace(aliceId, aliceHost.getId(), "web");
        aliceOtherProject = seedWorkspace(aliceId, aliceHost.getId(), "api");

        User bob = seedUser("bob-terminals@example.com");
        bobToken = jwtService.generateToken(bob);
        RunnerHost bobHost = seedHost(bob.getId(), "Poste de Bob");
        bobProject = seedWorkspace(bob.getId(), bobHost.getId(), "secret");
    }

    // ------------------------------------------------------------------ décors

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private RunnerHost seedHost(UUID userId, String name) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name(name).rootName("dev")
                .os("linux").shell("posix").elevated(false)
                .lastSeenAt(OffsetDateTime.now()).build());
    }

    private Workspace seedWorkspace(UUID userId, UUID hostId, String name) {
        return workspaceRepository.save(Workspace.builder().userId(userId).name(name)
                .hostId(hostId).projectPath(name)
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build());
    }

    private String claimUrl(UUID workspaceId) {
        return "/api/workspaces/" + workspaceId + "/terminal/live";
    }

    private String body(String sessionId) {
        return "{\"sessionId\":\"" + sessionId + "\"}";
    }

    private void claim(String token, UUID workspaceId, String sessionId) throws Exception {
        mockMvc.perform(post(claimUrl(workspaceId)).contextPath("/api")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(sessionId)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ cas nominal

    @Test
    void aTerminalTakesItsPlaceAndTheRegisterNamesIt() throws Exception {
        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("tab-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(4))
                .andExpect(jsonPath("$.live").value(1))
                .andExpect(jsonPath("$.terminals[0].workspaceName").value("web"))
                .andExpect(jsonPath("$.terminals[0].hostName").value("Poste CAGIP"));
    }

    @Test
    void theSameTabBeatingItsHeartDoesNotConsumeASecondPlace() throws Exception {
        claim(aliceToken, aliceProject.getId(), "tab-1");
        OffsetDateTime firstSeen = liveTerminalRepository
                .findByUserIdAndSessionId(aliceId, "tab-1").orElseThrow().getLastSeenAt();

        claim(aliceToken, aliceProject.getId(), "tab-1");

        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.live").value(1));
        assertThat(liveTerminalRepository.findByUserIdAndSessionId(aliceId, "tab-1").orElseThrow()
                .getLastSeenAt()).isAfterOrEqualTo(firstSeen);
    }

    @Test
    void aTabThatChangesProjectKeepsItsPlace() throws Exception {
        claim(aliceToken, aliceProject.getId(), "tab-1");
        claim(aliceToken, aliceOtherProject.getId(), "tab-1");

        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.live").value(1))
                .andExpect(jsonPath("$.terminals[0].workspaceName").value("api"));
    }

    // ------------------------------------------------------------------ le plafond

    @Test
    void theFifthTerminalIsRefusedWithTheSentenceTheScreenShows() throws Exception {
        for (int i = 1; i <= 4; i++) {
            claim(aliceToken, aliceProject.getId(), "tab-" + i);
        }

        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("tab-5")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("terminal_limit_reached"))
                .andExpect(jsonPath("$.message")
                        .value("Quatre terminaux actifs au maximum, fermez-en un pour en ouvrir un autre."));
    }

    @Test
    void aRefusedClaimLeavesNoTraceBehind() throws Exception {
        for (int i = 1; i <= 4; i++) {
            claim(aliceToken, aliceProject.getId(), "tab-" + i);
        }
        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("tab-5")))
                .andExpect(status().isConflict());

        // Le registre doit rester lisible : EXACTEMENT quatre, et pas de cinquième fantôme.
        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.live").value(4));
        assertThat(liveTerminalRepository.findByUserIdAndSessionId(aliceId, "tab-5")).isEmpty();
    }

    @Test
    void freeingAPlaceMakesTheNextClaimSucceed() throws Exception {
        for (int i = 1; i <= 4; i++) {
            claim(aliceToken, aliceProject.getId(), "tab-" + i);
        }

        mockMvc.perform(delete(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("sessionId", "tab-2"))
                .andExpect(status().isNoContent());

        claim(aliceToken, aliceProject.getId(), "tab-5");
        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.live").value(4));
    }

    @Test
    void releasingAPlaceThatIsAlreadyFreeIsNotAnError() throws Exception {
        mockMvc.perform(delete(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("sessionId", "never-opened"))
                .andExpect(status().isNoContent());
    }

    @Test
    void aTabClosedBrutallyFreesItsPlaceOnItsOwn() throws Exception {
        for (int i = 1; i <= 4; i++) {
            claim(aliceToken, aliceProject.getId(), "tab-" + i);
        }
        // Personne n'a envoyé de libération : l'onglet a simplement disparu. Sans expiration, le
        // compte resterait condamné.
        LiveTerminal abandoned = liveTerminalRepository
                .findByUserIdAndSessionId(aliceId, "tab-3").orElseThrow();
        abandoned.setLastSeenAt(OffsetDateTime.now().minusHours(1));
        liveTerminalRepository.save(abandoned);

        claim(aliceToken, aliceProject.getId(), "tab-5");

        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.live").value(4));
        assertThat(liveTerminalRepository.findByUserIdAndSessionId(aliceId, "tab-3")).isEmpty();
    }

    // ------------------------------------------------------------------ isolation user_id

    @Test
    void aTerminalCannotBeOpenedOnSomeoneElsesProject() throws Exception {
        mockMvc.perform(post(claimUrl(bobProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("tab-1")))
                .andExpect(status().isNotFound());

        assertThat(liveTerminalRepository.count()).isZero();
    }

    @Test
    void theCeilingOfOneUserNeverBlocksAnother() throws Exception {
        for (int i = 1; i <= 4; i++) {
            claim(aliceToken, aliceProject.getId(), "tab-" + i);
        }

        claim(bobToken, bobProject.getId(), "tab-1");

        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(jsonPath("$.live").value(1))
                .andExpect(jsonPath("$.terminals[0].workspaceName").value("secret"));
    }

    @Test
    void theRegisterNeverNamesSomeoneElsesProject() throws Exception {
        claim(bobToken, bobProject.getId(), "tab-1");

        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.live").value(0))
                .andExpect(jsonPath("$.terminals").isEmpty());
    }

    @Test
    void oneTabCannotFreeAnotherUsersPlace() throws Exception {
        claim(bobToken, bobProject.getId(), "tab-1");

        mockMvc.perform(delete(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("sessionId", "tab-1"))
                .andExpect(status().isNoContent());

        // La place de Bob est intacte : la libération est bornée à son propriétaire.
        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(jsonPath("$.live").value(1));
    }

    // ------------------------------------------------------------------ validation & accès

    @Test
    void anEmptySessionIdIsRefused() throws Exception {
        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSessionIdWithAForbiddenCharacterIsRefused() throws Exception {
        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("tab 1;drop")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theRegisterIsNotReadableWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/terminals/live").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ vue d'ensemble

    @Test
    void theHostOverviewShowsWhichProjectsHaveALiveTerminal() throws Exception {
        claim(aliceToken, aliceProject.getId(), "tab-1");

        mockMvc.perform(get("/api/runner-hosts/overview").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].liveTerminals").value(1))
                .andExpect(jsonPath("$[0].projects[?(@.name == 'web')].liveTerminal")
                        .value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$[0].projects[?(@.name == 'api')].liveTerminal")
                        .value(org.hamcrest.Matchers.hasItem(false)));
    }

    @Test
    void anotherUsersLiveTerminalNeverLightsUpMyOverview() throws Exception {
        claim(bobToken, bobProject.getId(), "tab-1");

        mockMvc.perform(get("/api/runner-hosts/overview").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].liveTerminals").value(0));
    }
}
