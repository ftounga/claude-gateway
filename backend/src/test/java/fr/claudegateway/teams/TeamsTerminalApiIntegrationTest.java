package fr.claudegateway.teams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.billing.seat.HostSeatMonthRepository;
import fr.claudegateway.quota.QuotaProperties;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>Le terminal Teams et son droit</b> (F-89 / SF-89-01), de bout en bout.
 *
 * <p>Deux choses s'y jouent, et la seconde est la plus importante. La première est mécanique : un
 * terminal Teams s'ouvre, s'ouvre une seule fois, n'est pas un projet, et part avec sa machine —
 * exactement le contrat du terminal du poste (F-74). La seconde est le <b>droit</b> : sans l'option
 * Teams, ce terminal <b>n'existe pas</b>, et l'appel qui l'ouvrirait ne crée rien.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamsTerminalApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private fr.claudegateway.runner.host.HostSpaceRepository hostSpaces;
    @Autowired private RunnerAuditRepository auditRepository;
    @Autowired private HostSeatMonthRepository seatMonths;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private QuotaProperties quotaProperties;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    private String aliceToken;
    private UUID aliceId;
    private RunnerHost aliceHost;
    private String bobToken;
    private RunnerHost bobHost;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        workspaceRepository.deleteAll();
        seatMonths.deleteAll();
        hostSpaces.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        // Des utilisateurs ORDINAIRES, et c'est le point : un ADMIN passerait par le bypass et ne
        // prouverait rien du droit.
        User alice = seedUser("alice-teams@example.com");
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), "EDENRED");
        subscribe(alice, PlanCode.PRO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);

        User bob = seedUser("bob-teams@example.com");
        bobToken = jwtService.generateToken(bob);
        bobHost = seedHost(bob.getId(), "Poste de Bob");
        // Bob a la Forge (plan Gold), mais PAS l'option Teams.
        subscribe(bob, PlanCode.GOLD, SubscriptionStatus.ACTIVE, null);
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    /** Un poste activé dans la Forge ET dans la Vigie, où vit le terminal Teams (F-106 / SF-106-03). */
    private RunnerHost seedHost(UUID userId, String name) {
        RunnerHost host = hostRepository.save(RunnerHost.builder().userId(userId).name(name).rootName("dev")
                .os("linux").shell("posix").elevated(false).build());
        for (fr.claudegateway.runner.host.ClientSpace space : fr.claudegateway.runner.host.ClientSpace.values()) {
            hostSpaces.save(fr.claudegateway.runner.host.HostSpace.builder().userId(userId)
                    .hostId(host.getId()).space(space).activatedAt(java.time.OffsetDateTime.now()).build());
        }
        return host;
    }

    private void subscribe(User user, PlanCode plan, SubscriptionStatus status,
            SubscriptionStatus teamsOption) {
        Subscription subscription = subscriptionRepository.findByUserId(user.getId())
                .orElseGet(() -> Subscription.builder().userId(user.getId()).build());
        subscription.setPlanCode(plan);
        subscription.setStatus(status);
        // L'option ATELIER est posée pour que Bob comme Alice aient la Forge : ce qui se teste ici
        // est l'option TEAMS, et un refus d'accès Forge masquerait le sujet.
        subscription.setAtelierOptionStatus(SubscriptionStatus.ACTIVE);
        subscription.setTeamsOptionStatus(teamsOption);
        subscriptionRepository.save(subscription);
    }

    private String teamsTerminalUrl(RunnerHost host) {
        return "/api/runner-hosts/" + host.getId() + "/teams-terminal";
    }

    private JsonNode openTeamsTerminal() throws Exception {
        String body = mockMvc.perform(post(teamsTerminalUrl(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    @DisplayName("un poste obtient son terminal Teams, marqué et posé à la racine")
    void aHostGetsItsTeamsTerminal() throws Exception {
        mockMvc.perform(post(teamsTerminalUrl(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Terminal Teams"))
                .andExpect(jsonPath("$.hostId").value(aliceHost.getId().toString()))
                .andExpect(jsonPath("$.projectPath").value(""))
                .andExpect(jsonPath("$.teamsTerminal").value(true))
                // Ce n'est PAS le terminal du poste : deux marques, deux lignes.
                .andExpect(jsonPath("$.hostTerminal").value(false))
                .andExpect(jsonPath("$.executionTarget").value("RUNNER"));
    }

    @Test
    @DisplayName("F-106 : un client hors de la Vigie n'a pas de terminal Teams — 409, rien n'est créé")
    void aHostOutsideTheVigieGetsNoTeamsTerminal() throws Exception {
        hostSpaces.findByUserIdAndHostId(aliceId, aliceHost.getId()).stream()
                .filter(row -> row.getSpace() == fr.claudegateway.runner.host.ClientSpace.VIGIE)
                .forEach(hostSpaces::delete);

        mockMvc.perform(post(teamsTerminalUrl(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("host_not_in_space"));

        assertThat(workspaceRepository.findAll()).isEmpty();
    }

    @Test
    void askingTwiceGivesTheSameTerminal() throws Exception {
        String first = openTeamsTerminal().get("id").asText();
        String second = openTeamsTerminal().get("id").asText();

        assertThat(second).isEqualTo(first);
        assertThat(workspaceRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("SANS L'OPTION : refus, et rien n'est créé")
    void withoutTheOptionNothingIsCreated() throws Exception {
        mockMvc.perform(post(teamsTerminalUrl(bobHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("teams_forbidden"));

        assertThat(workspaceRepository.findAll()).isEmpty();
    }

    @Test
    void anotherAccountsHostIsUnreachableAndCreatesNothing() throws Exception {
        mockMvc.perform(post(teamsTerminalUrl(bobHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());

        assertThat(workspaceRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("le terminal Teams n'est pas un projet : il n'apparaît pas sur la carte du poste")
    void theTeamsTerminalIsNotListedAmongTheProjects() throws Exception {
        openTeamsTerminal();

        mockMvc.perform(get("/api/runner-hosts/overview").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projects").isEmpty())
                .andExpect(jsonPath("$[0].teamsTerminalId").isNotEmpty())
                .andExpect(jsonPath("$[0].teamsTerminalLive").value(false))
                // Le terminal du POSTE, lui, n'a pas été ouvert : les deux sont bien distincts.
                .andExpect(jsonPath("$[0].hostTerminalId").isEmpty());
    }

    @Test
    @DisplayName("supprimer le poste emporte son terminal Teams, sans que F-69 s'y oppose")
    void deletingTheHostTakesItsTeamsTerminalWithIt() throws Exception {
        openTeamsTerminal();

        mockMvc.perform(delete("/api/runner-hosts/" + aliceHost.getId()).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        assertThat(workspaceRepository.findAll()).isEmpty();
        assertThat(hostRepository.findById(aliceHost.getId())).isEmpty();
    }

    @Test
    @DisplayName("le droit se lit d'un GET, et ne provoque jamais de refus")
    void theRightIsReadableWithoutBeingRefused() throws Exception {
        mockMvc.perform(get("/api/teams/access").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitled").value(true));

        mockMvc.perform(get("/api/teams/access").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitled").value(false));
    }

    @Test
    @DisplayName("L'OPTION OUVRE L'ACCÈS, ELLE N'AJOUTE PAS DE JETONS (D5)")
    void theOptionAddsNoTokens() throws Exception {
        long quotaWithOption = quotaProperties.tokensForPlan(PlanCode.PRO);

        // On retire l'option : le quota du plan ne bouge pas d'un jeton.
        Subscription subscription = subscriptionRepository.findByUserId(aliceId).orElseThrow();
        subscription.setTeamsOptionStatus(null);
        subscriptionRepository.save(subscription);

        assertThat(quotaProperties.tokensForPlan(PlanCode.PRO)).isEqualTo(quotaWithOption);
        // Et le droit, lui, s'est bien fermé : c'est l'accès qui bouge, jamais le quota.
        mockMvc.perform(get("/api/teams/access").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.entitled").value(false));
    }
}
