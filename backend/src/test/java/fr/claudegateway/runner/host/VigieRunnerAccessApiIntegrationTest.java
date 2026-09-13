package fr.claudegateway.runner.host;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.billing.seat.HostSeatMonthRepository;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>La Vigie ouvre le runner</b> (F-107 / SF-107-07), de bout en bout : un compte qui a la Vigie sans la
 * Forge appaire un poste, le voit, le coupe et ouvre son terminal Teams — mais aucun projet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VigieRunnerAccessApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private HostSpaceRepository spaceRepository;
    @Autowired private RunnerAuditRepository auditRepository;
    @Autowired private HostSeatMonthRepository seatMonths;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    /** Gold Vigie : la Vigie, pas la Forge. */
    private String veraToken;
    private User vera;
    private RunnerHost veraHost;
    private Workspace veraProject;
    /** Gold Forge : la Forge, pas la Vigie. */
    private String fredToken;
    private RunnerHost fredHost;
    /** Administrateur sans abonnement. */
    private String adaToken;
    private RunnerHost adaHost;
    /** Solo sans option : aucun espace. */
    private String noraToken;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        workspaceRepository.deleteAll();
        seatMonths.deleteAll();
        spaceRepository.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        vera = seedUser("vera-vigie@example.com", UserRole.USER);
        veraToken = jwtService.generateToken(vera);
        subscribe(vera, PlanCode.GOLD_VIGIE);
        veraHost = seedHost(vera.getId(), "EDENRED", ClientSpace.VIGIE);
        veraProject = workspaceRepository.save(Workspace.builder().userId(vera.getId())
                .name("web").hostId(veraHost.getId()).projectPath("web")
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build());

        User fred = seedUser("fred-forge@example.com", UserRole.USER);
        fredToken = jwtService.generateToken(fred);
        subscribe(fred, PlanCode.GOLD);
        fredHost = seedHost(fred.getId(), "Poste de Fred", ClientSpace.FORGE, ClientSpace.VIGIE);

        User ada = seedUser("ada-admin@example.com", UserRole.ADMIN);
        adaToken = jwtService.generateToken(ada);
        adaHost = seedHost(ada.getId(), "Poste d'Ada", ClientSpace.FORGE, ClientSpace.VIGIE);

        User nora = seedUser("nora-none@example.com", UserRole.USER);
        noraToken = jwtService.generateToken(nora);
        subscribe(nora, PlanCode.SOLO);
    }

    private User seedUser(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private void subscribe(User user, PlanCode plan) {
        Subscription subscription = subscriptionRepository.findByUserId(user.getId())
                .orElseGet(() -> Subscription.builder().userId(user.getId()).build());
        subscription.setPlanCode(plan);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setAtelierOptionStatus(null);
        subscription.setTeamsOptionStatus(null);
        subscriptionRepository.save(subscription);
    }

    private RunnerHost seedHost(UUID userId, String name, ClientSpace... spaces) {
        RunnerHost host = hostRepository.save(RunnerHost.builder().userId(userId).name(name).rootName("dev")
                .os("linux").shell("posix").elevated(false).build());
        for (ClientSpace space : spaces) {
            spaceRepository.save(HostSpace.builder().userId(userId).hostId(host.getId()).space(space)
                    .activatedAt(OffsetDateTime.now()).build());
        }
        return host;
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    private String hostUrl(RunnerHost host, String suffix) {
        return "/api/runner-hosts/" + host.getId() + suffix;
    }

    private String openTeamsTerminal(RunnerHost host, String token) throws Exception {
        String body = mockMvc.perform(as(post(hostUrl(host, "/teams-terminal")), token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    // ------------------------------------------------------------------ Vigie seule : le runner

    @Test
    @DisplayName("Vigie seule : connecter un client depuis la Vigie, l'appairer, le voir, le couper")
    void vigieOnlyPairsAndSeesItsHosts() throws Exception {
        String body = mockMvc.perform(as(post("/api/runner-hosts"), veraToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ACME\",\"space\":\"VIGIE\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newHostId = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(as(post("/api/runner-hosts/" + newHostId + "/pairing-code"), veraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNotEmpty());
        mockMvc.perform(as(get("/api/runner-hosts"), veraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(as(get("/api/runner-hosts/overview").param("space", "VIGIE"), veraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(as(get("/api/runner-hosts/spaces"), veraToken)).andExpect(status().isOk());
        mockMvc.perform(as(get(hostUrl(veraHost, "/status")), veraToken)).andExpect(status().isOk());
        mockMvc.perform(as(get(hostUrl(veraHost, "/tokens")), veraToken)).andExpect(status().isOk());
        mockMvc.perform(as(put(hostUrl(veraHost, "/mission")), veraToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"missionStatus\":\"PENDING\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(post(hostUrl(veraHost, "/kill")), veraToken)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Vigie seule : le terminal Teams s'ouvre et se lit")
    void vigieOnlyOpensTheTeamsTerminal() throws Exception {
        mockMvc.perform(as(get("/api/teams/access"), veraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitled").value(true));

        String teamsId = openTeamsTerminal(veraHost, veraToken);

        mockMvc.perform(as(get("/api/workspaces/" + teamsId), veraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamsTerminal").value(true));
        mockMvc.perform(as(get("/api/workspaces/" + teamsId + "/chat"), veraToken))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/workspaces/" + teamsId + "/chat/turn"), veraToken))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/workspaces/" + teamsId + "/engine"), veraToken))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/workspaces/" + teamsId + "/runner/status"), veraToken))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/workspaces/" + teamsId + "/teams/link"), veraToken))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/workspaces").param("space", "VIGIE"), veraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(teamsId));
    }

    @Test
    @DisplayName("Vigie seule : aucun projet, aucun terminal de poste, aucune vue Forge")
    void vigieOnlyIsRefusedOnTheForge() throws Exception {
        String projectUrl = "/api/workspaces/" + veraProject.getId();
        mockMvc.perform(as(get(projectUrl), veraToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("atelier_forbidden"));
        mockMvc.perform(as(get(projectUrl + "/chat"), veraToken)).andExpect(status().isForbidden());
        mockMvc.perform(as(get(projectUrl + "/runner/status"), veraToken)).andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/workspaces"), veraToken)).andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/runner-hosts/overview"), veraToken)).andExpect(status().isForbidden());
        mockMvc.perform(as(post(hostUrl(veraHost, "/terminal")), veraToken)).andExpect(status().isForbidden());
        mockMvc.perform(as(post(hostUrl(veraHost, "/projects")), veraToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"api\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get(hostUrl(veraHost, "/folders")), veraToken)).andExpect(status().isForbidden());
        mockMvc.perform(as(put(hostUrl(veraHost, "/spaces/FORGE")), veraToken)).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/runner-hosts"), veraToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Vigie seule : le terminal Teams d'un autre compte reste fermé")
    void vigieOnlyCannotReachAnotherTeamsTerminal() throws Exception {
        String otherTeams = openTeamsTerminal(adaHost, adaToken);
        mockMvc.perform(as(get("/api/workspaces/" + otherTeams), veraToken)).andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/workspaces/" + otherTeams + "/chat"), veraToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post(hostUrl(adaHost, "/teams-terminal")), veraToken))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ Forge seule : inchangé

    @Test
    @DisplayName("Forge seule : postes et projets ouverts, terminal Teams et Vigie refusés")
    void forgeOnlyIsUnchanged() throws Exception {
        mockMvc.perform(as(get("/api/runner-hosts"), fredToken)).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/runner-hosts/overview"), fredToken)).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/workspaces"), fredToken)).andExpect(status().isOk());
        mockMvc.perform(as(post(hostUrl(fredHost, "/terminal")), fredToken)).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/teams/access"), fredToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitled").value(false));
        mockMvc.perform(as(post(hostUrl(fredHost, "/teams-terminal")), fredToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/runner-hosts/overview").param("space", "VIGIE"), fredToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/workspaces").param("space", "VIGIE"), fredToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ ADMIN et sans droit

    @Test
    @DisplayName("ADMIN : tout est ouvert")
    void adminHasEverything() throws Exception {
        mockMvc.perform(as(get("/api/runner-hosts/overview"), adaToken)).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/runner-hosts/overview").param("space", "VIGIE"), adaToken))
                .andExpect(status().isOk());
        mockMvc.perform(as(post(hostUrl(adaHost, "/terminal")), adaToken)).andExpect(status().isOk());
        String teamsId = openTeamsTerminal(adaHost, adaToken);
        mockMvc.perform(as(get("/api/workspaces/" + teamsId + "/chat"), adaToken)).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/workspaces"), adaToken)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("sans espace : le runner reste fermé")
    void noSpaceIsRefused() throws Exception {
        mockMvc.perform(as(get("/api/runner-hosts"), noraToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("atelier_forbidden"));
        mockMvc.perform(as(get("/api/teams/access"), noraToken)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("le téléchargement du runner ne demande aucun droit")
    void runnerDownloadIsPublic() throws Exception {
        mockMvc.perform(get("/api/runner/download").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("runner_jar_unavailable"));
    }
}
