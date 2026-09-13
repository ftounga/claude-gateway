package fr.claudegateway.runner.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <b>Les espaces d'un client</b> (F-106 / SF-106-01), de bout en bout : un poste, deux regards, et
 * rien qui ne se copie ni ne se perd.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HostSpacesApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private HostSpaceRepository spaceRepository;
    @Autowired private RunnerAuditRepository auditRepository;
    @Autowired private HostSeatMonthRepository seatMonths;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private String aliceToken;
    private User alice;
    private RunnerHost aliceHost;
    private String bobToken;
    private RunnerHost bobHost;
    /** Un compte ordinaire : la Forge, sans l'option Teams. */
    private String carolToken;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        workspaceRepository.deleteAll();
        seatMonths.deleteAll();
        spaceRepository.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        alice = seedUser("alice-spaces@example.com", UserRole.ADMIN);
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), "EDENRED");

        User bob = seedUser("bob-spaces@example.com", UserRole.ADMIN);
        bobToken = jwtService.generateToken(bob);
        bobHost = seedHost(bob.getId(), "Poste de Bob");

        User carol = seedUser("carol-spaces@example.com", UserRole.USER);
        carolToken = jwtService.generateToken(carol);
        Subscription subscription = subscriptionRepository.findByUserId(carol.getId())
                .orElseGet(() -> Subscription.builder().userId(carol.getId()).build());
        subscription.setPlanCode(PlanCode.GOLD);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setAtelierOptionStatus(SubscriptionStatus.ACTIVE);
        subscription.setTeamsOptionStatus(null);
        subscriptionRepository.save(subscription);
    }

    private User seedUser(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private RunnerHost seedHost(UUID userId, String name) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name(name).rootName("dev")
                .os("linux").shell("posix").elevated(false).build());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    private String spaceUrl(RunnerHost host, String space) {
        return "/api/runner-hosts/" + host.getId() + "/spaces/" + space;
    }

    @Test
    @DisplayName("un poste sans ligne est dans la Forge, pas dans la Vigie")
    void anExistingHostIsInTheForgeOnly() throws Exception {
        mockMvc.perform(as(get("/api/runner-hosts/overview"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("EDENRED"))
                .andExpect(jsonPath("$[0].spaces", contains("FORGE")));
        mockMvc.perform(as(get("/api/runner-hosts/overview").param("space", "VIGIE"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(as(get("/api/runner-hosts/spaces"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].hostId").value(aliceHost.getId().toString()))
                .andExpect(jsonPath("$[0].spaces", contains("FORGE")));
    }

    @Test
    @DisplayName("activer dans la Vigie : le même poste, dans les deux vues, sans doublon")
    void activatingInTheVigieShowsTheSameHostInBothViews() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(as(put(spaceUrl(aliceHost, "vigie")), aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("EDENRED"))
                    .andExpect(jsonPath("$.spaces", contains("FORGE", "VIGIE")));
        }
        assertThat(spaceRepository.findByUserIdAndHostId(alice.getId(), aliceHost.getId())).hasSize(2);
        mockMvc.perform(as(get("/api/runner-hosts/overview").param("space", "VIGIE"), aliceToken))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(aliceHost.getId().toString()))
                .andExpect(jsonPath("$[0].spaces", contains("FORGE", "VIGIE")));
        mockMvc.perform(as(get("/api/runner-hosts/overview"), aliceToken))
                .andExpect(jsonPath("$[0].spaces", contains("FORGE", "VIGIE")));
    }

    @Test
    @DisplayName("retirer de la Vigie ne supprime rien ; le dernier espace ne se retire pas")
    void removingKeepsEverythingAndTheLastSpaceStays() throws Exception {
        Workspace project = workspaceRepository.save(Workspace.builder().userId(alice.getId())
                .name("web").hostId(aliceHost.getId()).projectPath("web")
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build());
        mockMvc.perform(as(put(spaceUrl(aliceHost, "VIGIE")), aliceToken)).andExpect(status().isOk());

        mockMvc.perform(as(delete(spaceUrl(aliceHost, "VIGIE")), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaces", contains("FORGE")));
        assertThat(workspaceRepository.findById(project.getId())).isPresent();
        assertThat(hostRepository.findById(aliceHost.getId())).isPresent();

        mockMvc.perform(as(delete(spaceUrl(aliceHost, "FORGE")), aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("host_last_space"));
        mockMvc.perform(as(get("/api/runner-hosts/overview"), aliceToken))
                .andExpect(jsonPath("$[?(@.name == 'EDENRED')].spaces[0]").value("FORGE"));
    }

    @Test
    @DisplayName("un client connecté depuis la Vigie n'apparaît pas dans la Forge")
    void aHostCreatedInTheVigieIsNotInTheForge() throws Exception {
        String body = mockMvc.perform(as(post("/api/runner-hosts"), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CAGIP\",\"space\":\"VIGIE\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID created = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(as(get("/api/runner-hosts/overview"), aliceToken))
                .andExpect(jsonPath("$[*].name", not(hasItem("CAGIP"))));
        mockMvc.perform(as(get("/api/runner-hosts/overview").param("space", "VIGIE"), aliceToken))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(created.toString()))
                .andExpect(jsonPath("$[0].spaces", contains("VIGIE")));
        // Sans champ : la Forge, comme avant.
        mockMvc.perform(as(post("/api/runner-hosts"), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"FREE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/runner-hosts/overview"), aliceToken))
                .andExpect(jsonPath("$[*].name", hasItem("FREE")));
    }

    @Test
    @DisplayName("« Hébergé » ne vit que dans la Forge")
    void theHostedPseudoHostIsForgeOnly() throws Exception {
        workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("repo")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build());
        mockMvc.perform(as(put(spaceUrl(aliceHost, "VIGIE")), aliceToken)).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/runner-hosts/overview"), aliceToken))
                .andExpect(jsonPath("$[?(@.virtual == true)].spaces[0]").value("FORGE"));
        mockMvc.perform(as(get("/api/runner-hosts/overview").param("space", "VIGIE"), aliceToken))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].virtual").value(false));
    }

    @Test
    @DisplayName("la clôture de mission est commune aux deux espaces")
    void missionClosureIsReadInBothSpaces() throws Exception {
        mockMvc.perform(as(put(spaceUrl(aliceHost, "VIGIE")), aliceToken)).andExpect(status().isOk());
        mockMvc.perform(as(put("/api/runner-hosts/" + aliceHost.getId() + "/mission"), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"missionStatus\":\"CLOSED\"}"))
                .andExpect(status().isOk());
        for (String space : List.of("FORGE", "VIGIE")) {
            mockMvc.perform(as(get("/api/runner-hosts/overview").param("space", space), aliceToken))
                    .andExpect(jsonPath("$[0].missionStatus").value("CLOSED"));
        }
    }

    @Test
    @DisplayName("isolation : le poste d'autrui est introuvable et rien n'est écrit")
    void anotherUsersHostIsNotFound() throws Exception {
        mockMvc.perform(as(put(spaceUrl(bobHost, "VIGIE")), aliceToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(delete(spaceUrl(bobHost, "VIGIE")), aliceToken))
                .andExpect(status().isNotFound());
        assertThat(spaceRepository.findAll()).isEmpty();
        mockMvc.perform(as(get("/api/runner-hosts/spaces"), aliceToken))
                .andExpect(jsonPath("$[*].name", not(hasItem("Poste de Bob"))));
        mockMvc.perform(as(get("/api/runner-hosts/spaces"), bobToken))
                .andExpect(jsonPath("$[*].name", contains("Poste de Bob")));
    }

    @Test
    @DisplayName("un espace inconnu est refusé")
    void anUnknownSpaceIsRefused() throws Exception {
        mockMvc.perform(as(put(spaceUrl(aliceHost, "atelier")), aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_space"));
        mockMvc.perform(as(get("/api/runner-hosts/overview").param("space", "radar"), aliceToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("la Vigie exige le droit Teams (compte ordinaire) ; la Forge non")
    void theVigieRequiresTheTeamsRight() throws Exception {
        mockMvc.perform(as(get("/api/runner-hosts/overview").param("space", "VIGIE"), carolToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/runner-hosts"), carolToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CAGIP\",\"space\":\"VIGIE\"}"))
                .andExpect(status().isForbidden());
        assertThat(hostRepository.findAll()).extracting(RunnerHost::getName).doesNotContain("CAGIP");
        mockMvc.perform(as(get("/api/runner-hosts/overview"), carolToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("supprimer le poste efface ses lignes d'espace")
    void deletingTheHostPurgesItsSpaces() throws Exception {
        mockMvc.perform(as(put(spaceUrl(aliceHost, "VIGIE")), aliceToken)).andExpect(status().isOk());
        mockMvc.perform(as(delete("/api/runner-hosts/" + aliceHost.getId()), aliceToken))
                .andExpect(status().isNoContent());
        assertThat(spaceRepository.findByUserIdAndHostId(alice.getId(), aliceHost.getId())).isEmpty();
    }

    @Test
    @DisplayName("migration 091 : chaque poste existant est activé dans la Forge, aucun dans la Vigie")
    void theMigrationActivatesEveryExistingHostInTheForge() throws Exception {
        String xml = new String(new ClassPathResource("db/changelog/migrations/091-host-spaces.xml")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Matcher h2 = Pattern.compile(
                "id=\"091-host-spaces-forge-h2\".*?<sql>(.*?)</sql>", Pattern.DOTALL).matcher(xml);
        assertThat(h2.find()).isTrue();

        jdbc.execute(h2.group(1));

        assertThat(spaceRepository.findAll()).hasSize(2)
                .allMatch(row -> row.getSpace() == ClientSpace.FORGE);
        assertThat(spaceRepository.findByUserIdAndHostId(alice.getId(), aliceHost.getId()))
                .singleElement().satisfies(row -> assertThat(row.getActivatedAt()).isNotNull());
    }
}
