package fr.claudegateway.runner.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import fr.claudegateway.runner.RunnerToken;
import fr.claudegateway.runner.RunnerTokenRepository;
import fr.claudegateway.runner.audit.RunnerAudit;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRole;
import fr.claudegateway.user.UserRepository;

/**
 * L'<b>état de mission</b> d'un poste, de bout en bout (F-60 / SF-60-01).
 *
 * <p>Deux choses s'y vérifient, et la seconde est la plus importante : que l'état se déclare et se
 * relise, et que le <b>déclarer ne coupe rien</b>. Clôturer une mission range un poste ; le
 * coupe-circuit, lui, est un autre endpoint, et il doit le rester.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HostMissionApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerTokenRepository tokenRepository;
    @Autowired private RunnerAuditRepository auditRepository;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private String plainToken;
    private RunnerHost aliceHost;
    private RunnerHost bobHost;
    private Workspace aliceProject;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        tokenRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-mission@example.com", UserRole.ADMIN);
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), "Poste CAGIP");
        aliceProject = seedWorkspace(alice.getId(), aliceHost.getId(), "web");
        seedToken(alice.getId(), aliceHost.getId());
        seedAudit(alice.getId(), aliceHost.getId(), aliceProject.getId());

        User bob = seedUser("bob-mission@example.com", UserRole.ADMIN);
        bobToken = jwtService.generateToken(bob);
        bobHost = seedHost(bob.getId(), "Poste de Bob");

        plainToken = jwtService.generateToken(seedUser("plain-mission@example.com", UserRole.USER));
    }

    private String url(UUID hostId) {
        return "/api/runner-hosts/" + hostId + "/mission";
    }

    private User seedUser(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
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

    private void seedToken(UUID userId, UUID hostId) {
        tokenRepository.save(RunnerToken.builder().userId(userId).hostId(hostId)
                .tokenHash("hash-" + UUID.randomUUID()).label("poste")
                .expiresAt(OffsetDateTime.now().plusDays(30)).build());
    }

    private void seedAudit(UUID userId, UUID hostId, UUID workspaceId) {
        auditRepository.save(RunnerAudit.builder().userId(userId).hostId(hostId)
                .workspaceId(workspaceId).callId(UUID.randomUUID().toString()).tool("bash")
                .target("cible").outcome("OK").createdAt(OffsetDateTime.now()).build());
    }

    private String body(String status) {
        return "{\"missionStatus\":\"" + status + "\"}";
    }

    // ------------------------------------------------------------------ cas nominal

    @Test
    void aFreshHostReadsAsAMissionInProgress() throws Exception {
        mockMvc.perform(get("/api/runner-hosts").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].missionStatus").value("ACTIVE"));
    }

    @Test
    void theOwnerDeclaresWhereTheMissionStands() throws Exception {
        mockMvc.perform(put(url(aliceHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("PENDING")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missionStatus").value("PENDING"))
                .andExpect(jsonPath("$.name").value("Poste CAGIP"));

        mockMvc.perform(get("/api/runner-hosts/" + aliceHost.getId()).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missionStatus").value("PENDING"));
    }

    @Test
    void reapplyingTheSameStatusIsNotAnError() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(put(url(aliceHost.getId())).contextPath("/api")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON).content(body("CLOSED")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.missionStatus").value("CLOSED"));
        }
    }

    @Test
    void theOverviewStillCarriesAClosedMission() throws Exception {
        // « Se ranger sans disparaître » : la gateway ne filtre rien, c'est l'écran qui range.
        mockMvc.perform(put(url(aliceHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("CLOSED")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/runner-hosts/overview").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].missionStatus").value("CLOSED"))
                .andExpect(jsonPath("$[0].projects.length()").value(1));
    }

    // ---------------------------------------------------- ce que le geste ne fait PAS

    @Test
    void closingAMissionCutsNeitherTheRunnerNorTheHistory() throws Exception {
        mockMvc.perform(put(url(aliceHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("CLOSED")))
                .andExpect(status().isOk());

        // Le jeton du poste vit toujours, et n'est pas révoqué : clôturer n'est pas un coupe-circuit.
        assertThat(tokenRepository.findAll())
                .hasSize(1)
                .allSatisfy(token -> assertThat(token.getRevokedAt()).isNull());
        // Le projet reste rattaché, et sa cible d'exécution n'est pas ramenée à SANDBOX.
        assertThat(workspaceRepository.findById(aliceProject.getId())).hasValueSatisfying(w -> {
            assertThat(w.getHostId()).isEqualTo(aliceHost.getId());
            assertThat(w.getExecutionTarget()).isEqualTo(WorkspaceExecutionTarget.RUNNER);
        });
        // L'historique n'est pas touché.
        assertThat(auditRepository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ cas d'erreur

    @Test
    void refusesAStatusOutsideTheContract() throws Exception {
        mockMvc.perform(put(url(aliceHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("DONE")))
                .andExpect(status().isBadRequest());

        assertThat(hostRepository.findById(aliceHost.getId()))
                .hasValueSatisfying(h ->
                        assertThat(h.getMissionStatus()).isEqualTo(HostMissionStatus.ACTIVE));
    }

    @Test
    void refusesALowercaseStatus() throws Exception {
        // Une énumération de contrat n'est pas un texte libre : la tolérance à la casse est une
        // dette qui finit par accepter des valeurs qu'on n'a jamais voulues.
        mockMvc.perform(put(url(aliceHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("closed")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesAnEmptyBody() throws Exception {
        mockMvc.perform(put(url(aliceHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownHostIsNotFound() throws Exception {
        mockMvc.perform(put(url(UUID.randomUUID())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("PENDING")))
                .andExpect(status().isNotFound());
    }

    @Test
    void theMissionOfAnotherAccountsHostCannotBeTouched() throws Exception {
        // Isolation user_id, sans oracle d'existence : 404 comme un poste inconnu, jamais 403.
        mockMvc.perform(put(url(bobHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("CLOSED")))
                .andExpect(status().isNotFound());

        assertThat(hostRepository.findById(bobHost.getId()))
                .hasValueSatisfying(h ->
                        assertThat(h.getMissionStatus()).isEqualTo(HostMissionStatus.ACTIVE));
    }

    @Test
    void bobKeepsControlOfHisOwnMission() throws Exception {
        mockMvc.perform(put(url(bobHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("CLOSED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missionStatus").value("CLOSED"));
    }

    @Test
    void theGestureIsClosedWithoutAtelierAccess() throws Exception {
        mockMvc.perform(put(url(aliceHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + plainToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("PENDING")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theGestureIsClosedWithoutJwt() throws Exception {
        mockMvc.perform(put(url(aliceHost.getId())).contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(body("PENDING")))
                .andExpect(status().isUnauthorized());
    }
}
