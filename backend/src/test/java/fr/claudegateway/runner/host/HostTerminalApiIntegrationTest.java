package fr.claudegateway.runner.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.seat.HostSeatMonthRepository;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>Le terminal du poste</b> (F-74 / SF-74-01), de bout en bout.
 *
 * <p>Le trou de parcours qu'il bouche : le premier jour d'une mission, la racine est vide — pas de
 * projet, donc pas de terminal, donc aucun moyen de cloner un dépôt depuis le produit.</p>
 *
 * <p>Le point dur de ces tests n'est pas la création : c'est ce que le terminal du poste <b>n'est
 * pas</b>. Il ne compte pas comme projet, donc il n'apparaît pas sur la carte, il n'interdit pas
 * d'ouvrir un vrai projet sur la racine, il ne bloque pas la suppression du poste (F-69) — et il
 * part avec lui. Et il ne fait bouger <b>aucune</b> ligne de facturation (F-65).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HostTerminalApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerAuditRepository auditRepository;
    @Autowired private HostSeatMonthRepository seatMonths;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    private String aliceToken;
    private UUID aliceId;
    private RunnerHost aliceHost;
    private RunnerHost bobHost;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        workspaceRepository.deleteAll();
        seatMonths.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-hostterminal@example.com", UserRole.ADMIN);
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), "EDENRED");

        User bob = seedUser("bob-hostterminal@example.com", UserRole.ADMIN);
        bobHost = seedHost(bob.getId(), "Poste de Bob");
    }

    private User seedUser(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private RunnerHost seedHost(UUID userId, String name) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name(name).rootName("dev")
                .os("linux").shell("posix").elevated(false).build());
    }

    private String terminalUrl(RunnerHost host) {
        return "/api/runner-hosts/" + host.getId() + "/terminal";
    }

    private JsonNode openTerminal() throws Exception {
        String body = mockMvc.perform(post(terminalUrl(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void aHostGetsATerminalAtItsRoot() throws Exception {
        mockMvc.perform(post(terminalUrl(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Terminal du poste"))
                .andExpect(jsonPath("$.hostId").value(aliceHost.getId().toString()))
                .andExpect(jsonPath("$.projectPath").value(""))
                .andExpect(jsonPath("$.hostTerminal").value(true))
                .andExpect(jsonPath("$.executionTarget").value("RUNNER"))
                // P4 : la porte de confirmation de F-73, armée ici comme partout.
                .andExpect(jsonPath("$.askBeforeBash").value(true));
    }

    @Test
    void askingTwiceGivesTheSameTerminal() throws Exception {
        String first = openTerminal().get("id").asText();
        String second = openTerminal().get("id").asText();

        assertThat(second).isEqualTo(first);
        assertThat(workspaceRepository.findFirstByUserIdAndHostIdAndHostTerminalTrue(aliceId,
                aliceHost.getId())).isPresent();
        assertThat(workspaceRepository.findAll()).hasSize(1);
    }

    @Test
    void anotherAccountsHostIsUnreachableAndCreatesNothing() throws Exception {
        mockMvc.perform(post(terminalUrl(bobHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());

        assertThat(workspaceRepository.findAll()).isEmpty();
    }

    @Test
    void theHostTerminalIsNotListedAmongTheProjects() throws Exception {
        openTerminal();

        mockMvc.perform(get("/api/runner-hosts/overview").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projects").isEmpty())
                .andExpect(jsonPath("$[0].hostTerminalId").isNotEmpty())
                .andExpect(jsonPath("$[0].hostTerminalLive").value(false));
    }

    @Test
    void aRealProjectCanStillBeOpenedOnTheRoot() throws Exception {
        openTerminal();

        // Le terminal occupe le chemin « », mais ce n'est pas un projet : le contrôle de doublon
        // de F-72 ne doit pas le voir.
        mockMvc.perform(post("/api/runner-hosts/" + aliceHost.getId() + "/projects")
                        .contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("EDENRED"))
                .andExpect(jsonPath("$.hostTerminal").value(false));
    }

    @Test
    void deletingTheHostTakesItsTerminalWithIt() throws Exception {
        openTerminal();

        // Le terminal ne compte pas comme projet : la garde de F-69 laisse passer.
        mockMvc.perform(delete("/api/runner-hosts/" + aliceHost.getId()).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        assertThat(workspaceRepository.findAll()).isEmpty();
        assertThat(hostRepository.findById(aliceHost.getId())).isEmpty();
    }

    @Test
    void deletingAHostThatStillCarriesAProjectIsStillRefused() throws Exception {
        openTerminal();
        mockMvc.perform(post("/api/runner-hosts/" + aliceHost.getId() + "/projects")
                        .contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"api\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/runner-hosts/" + aliceHost.getId()).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict());

        // Le refus ne fait RIEN : ni le projet ni le terminal ne sont touchés.
        assertThat(workspaceRepository.findAll()).hasSize(2);
        assertThat(hostRepository.findById(aliceHost.getId())).isPresent();
    }

    @Test
    void openingAHostTerminalBillsNothing() throws Exception {
        openTerminal();

        // F-65 facture des POSTES, pas des terminaux. Ouvrir celui-ci ne crée, ne clôt ni ne rouvre
        // aucune mission : le registre des mois-postes ne bouge pas.
        assertThat(seatMonths.findAll()).isEmpty();
    }
}
