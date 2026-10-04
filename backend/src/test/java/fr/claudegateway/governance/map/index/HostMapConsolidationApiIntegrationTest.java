package fr.claudegateway.governance.map.index;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * {@code GET /api/governance/hosts/{hostRef}/map/consolidation} (F-174 / SF-174-06) : garde, isolation,
 * et forme de la réponse.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HostMapConsolidationApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private JwtService jwtService;
    @Autowired private HostMapSectionRepository sections;
    @Autowired private HostMapFactRepository facts;

    private String aliceToken;
    private String bobToken;
    private UUID aliceHost;

    @BeforeEach
    void setUp() {
        facts.deleteAll();
        sections.deleteAll();
        workspaceRepository.deleteAll();
        hosts.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-consolidation@example.com");
        aliceToken = jwtService.generateToken(alice);
        aliceHost = hosts.save(RunnerHost.builder().userId(alice.getId()).name("CAGIP").build()).getId();
        bobToken = jwtService.generateToken(seedUser("bob-consolidation@example.com"));

        HostMapSection section = sections.save(HostMapSection.builder().userId(alice.getId()).hostId(aliceHost)
                .path("acces.md").heading("Proxy").ordinal(1).fingerprint("f").status("DONE")
                .createdAt(OffsetDateTime.now()).build());
        for (int line : new int[] {3, 7}) {
            facts.save(HostMapFact.builder().userId(alice.getId()).hostId(aliceHost).sectionId(section.getId())
                    .path("acces.md").heading("Proxy").lineNo(line)
                    .text("- le proxy Netskope coupe les websockets").kind("FAIT").build());
        }
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private String path(String ref) {
        return "/api/governance/hosts/" + ref + "/map/consolidation";
    }

    @Test
    @DisplayName("sans jeton : 401 ; le poste d'un autre compte : 404")
    void guarded() throws Exception {
        mockMvc.perform(get(path(aliceHost.toString())).contextPath("/api")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path(aliceHost.toString())).contextPath("/api")
                .header("Authorization", "Bearer " + bobToken)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("le propriétaire lit les propositions, chacune avec sa demande à la Forge")
    void ownerReadsProposals() throws Exception {
        mockMvc.perform(get(path(aliceHost.toString())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(true))
                .andExpect(jsonPath("$.proposals[0].kind").value("DOUBLON"))
                .andExpect(jsonPath("$.proposals[0].facts.length()").value(2))
                .andExpect(jsonPath("$.proposals[0].request").isNotEmpty());
    }

    @Test
    @DisplayName("« Hébergé » n'a pas de carte : rien à consolider")
    void hostedHasNothing() throws Exception {
        mockMvc.perform(get(path("hosted")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(false))
                .andExpect(jsonPath("$.proposals.length()").value(0));
    }
}
