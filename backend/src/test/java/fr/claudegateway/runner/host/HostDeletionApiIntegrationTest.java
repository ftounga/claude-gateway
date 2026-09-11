package fr.claudegateway.runner.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.RunnerPairingCodeRepository;
import fr.claudegateway.runner.RunnerToken;
import fr.claudegateway.runner.RunnerTokenRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>Supprimer un poste</b> — et le refuser tant qu'il porte des projets (F-69 / SF-69-01).
 *
 * <p>Décision du PO : <b>pas de cascade</b>. Une cascade effacerait des conversations que
 * l'utilisateur ne voyait même plus ; le refus l'oblige à regarder ce qu'il jette, et le message lui
 * dit combien il en reste.</p>
 *
 * <p>Le point dur du test n'est pas le code 409, c'est ce que le refus <b>ne fait pas</b> : un refus
 * qui aurait déjà révoqué les jetons et détaché les projets ne serait pas un refus. Avant F-69, ce
 * chemin détachait les projets — ni cascade ni refus, une troisième voie que personne n'avait
 * choisie.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HostDeletionApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerTokenRepository tokenRepository;
    @Autowired private RunnerPairingCodeRepository pairingCodes;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private String plainToken;
    private RunnerHost aliceHost;
    private RunnerHost aliceEmptyHost;
    private RunnerHost bobHost;
    private Workspace aliceProject;
    private Workspace aliceSecondProject;

    @BeforeEach
    void setUp() {
        pairingCodes.deleteAll();
        tokenRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-host-delete@example.com", UserRole.ADMIN);
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), "Poste CAGIP");
        aliceEmptyHost = seedHost(alice.getId(), "Poste rendu");
        aliceProject = seedProject(alice.getId(), aliceHost.getId(), "client-cagip");
        aliceSecondProject = seedProject(alice.getId(), aliceHost.getId(), "client-cagip-audit");
        seedToken(alice.getId(), aliceHost.getId());

        User bob = seedUser("bob-host-delete@example.com", UserRole.ADMIN);
        bobToken = jwtService.generateToken(bob);
        bobHost = seedHost(bob.getId(), "Poste de Bob");

        plainToken = jwtService.generateToken(
                seedUser("plain-host-delete@example.com", UserRole.USER));
    }

    private String url(UUID hostId) {
        return "/api/runner-hosts/" + hostId;
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

    private Workspace seedProject(UUID userId, UUID hostId, String folder) {
        return workspaceRepository.save(Workspace.builder().userId(userId).name(folder)
                .hostId(hostId).projectPath(folder)
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build());
    }

    private void seedToken(UUID userId, UUID hostId) {
        tokenRepository.save(RunnerToken.builder().userId(userId).hostId(hostId)
                .tokenHash("hash-" + UUID.randomUUID()).label("poste")
                .expiresAt(OffsetDateTime.now().plusDays(30)).build());
    }

    private void deleteProject(UUID workspaceId) {
        workspaceRepository.deleteById(workspaceId);
    }

    // ------------------------------------------------------------------ le refus

    @Test
    void aHostThatStillCarriesProjectsIsNotDeleted() throws Exception {
        mockMvc.perform(delete(url(aliceHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("host_has_projects"))
                // Le message dit COMBIEN, et où aller les chercher.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("2 projets")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Poste CAGIP")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Forge")));

        assertThat(hostRepository.findById(aliceHost.getId())).isPresent();
    }

    @Test
    void aRefusalChangesStrictlyNothing() throws Exception {
        mockMvc.perform(delete(url(aliceHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict());

        // Aucun jeton révoqué : la machine reste joignable, on n'a rien débranché en refusant.
        assertThat(tokenRepository.findAll())
                .hasSize(1)
                .allSatisfy(token -> assertThat(token.getRevokedAt()).isNull());
        // Aucun projet détaché, aucune cible ramenée à SANDBOX : le détachement d'avant F-69 est
        // bien parti, et le refus ne l'a pas remplacé par une demi-mesure.
        assertThat(workspaceRepository.findById(aliceProject.getId())).hasValueSatisfying(w -> {
            assertThat(w.getHostId()).isEqualTo(aliceHost.getId());
            assertThat(w.getProjectPath()).isEqualTo("client-cagip");
            assertThat(w.getExecutionTarget()).isEqualTo(WorkspaceExecutionTarget.RUNNER);
        });
        assertThat(workspaceRepository.findById(aliceSecondProject.getId())).isPresent();
    }

    @Test
    void theMessageCountsASingleProjectInTheSingular() throws Exception {
        deleteProject(aliceSecondProject.getId());

        mockMvc.perform(delete(url(aliceHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("1 projet.")));
    }

    // ------------------------------------------------------------------ cas nominal

    @Test
    void anEmptyHostIsDeleted() throws Exception {
        mockMvc.perform(delete(url(aliceEmptyHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        assertThat(hostRepository.findById(aliceEmptyHost.getId())).isEmpty();
        // Le poste voisin, lui, n'a pas bougé.
        assertThat(hostRepository.findById(aliceHost.getId())).isPresent();
    }

    @Test
    void theHostBecomesDeletableOnceItsProjectsAreGone() throws Exception {
        deleteProject(aliceProject.getId());
        deleteProject(aliceSecondProject.getId());

        mockMvc.perform(delete(url(aliceHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        assertThat(hostRepository.findById(aliceHost.getId())).isEmpty();
        // Les jetons du poste partent avec lui : plus de machine, plus d'authentification.
        assertThat(tokenRepository.findAll()).isEmpty();
    }

    // ------------------------------------------------------------------ isolation

    @Test
    void theHostOfAnotherAccountCannotBeDeleted() throws Exception {
        // 404 et non 409 : sans possession, on ne dit même pas combien de projets il porte.
        mockMvc.perform(delete(url(bobHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());

        assertThat(hostRepository.findById(bobHost.getId())).isPresent();
    }

    @Test
    void bobDeletesHisOwnEmptyHost() throws Exception {
        mockMvc.perform(delete(url(bobHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNoContent());

        assertThat(hostRepository.findById(bobHost.getId())).isEmpty();
        assertThat(hostRepository.findById(aliceHost.getId())).isPresent();
    }

    @Test
    void anUnknownHostIsNotFound() throws Exception {
        mockMvc.perform(delete(url(UUID.randomUUID())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void theGestureIsClosedWithoutAtelierAccess() throws Exception {
        mockMvc.perform(delete(url(aliceEmptyHost.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());

        assertThat(hostRepository.findById(aliceEmptyHost.getId())).isPresent();
    }

    @Test
    void theGestureIsClosedWithoutJwt() throws Exception {
        mockMvc.perform(delete(url(aliceEmptyHost.getId())).contextPath("/api"))
                .andExpect(status().isUnauthorized());

        assertThat(hostRepository.findById(aliceEmptyHost.getId())).isPresent();
    }
}
