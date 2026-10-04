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
 * {@code GET /api/governance/hosts/{hostRef}/map/graph} et {@code …/map/entities/{nodeId}}
 * (F-173 / SF-173-01) : garde, isolation, forme de la réponse.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HostMapGraphApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private JwtService jwtService;
    @Autowired private HostMapSectionRepository sections;
    @Autowired private HostMapFactRepository facts;
    @Autowired private HostMapEntityRepository entities;
    @Autowired private HostMapRelationRepository relations;

    private String aliceToken;
    private String bobToken;
    private UUID aliceHost;

    @BeforeEach
    void setUp() {
        relations.deleteAll();
        entities.deleteAll();
        facts.deleteAll();
        sections.deleteAll();
        workspaceRepository.deleteAll();
        hosts.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-graph@example.com");
        aliceToken = jwtService.generateToken(alice);
        aliceHost = hosts.save(RunnerHost.builder().userId(alice.getId()).name("CAGIP").build()).getId();
        bobToken = jwtService.generateToken(seedUser("bob-graph@example.com"));

        HostMapSection section = sections.save(HostMapSection.builder().userId(alice.getId()).hostId(aliceHost)
                .path("infra.md").heading("Clusters").ordinal(1).fingerprint("f").status("DONE")
                .createdAt(OffsetDateTime.now()).build());
        for (String[] e : new String[][] {{"cluster", "lzi-prod"}, {"compte_aws", "compte prod"}}) {
            entities.save(HostMapEntity.builder().userId(alice.getId()).hostId(aliceHost).sectionId(section.getId())
                    .path("infra.md").heading("Clusters").kind(e[0]).label(e[1]).labelNorm(e[1])
                    .origin(HostMapEntity.MODELE).build());
        }
        relations.save(HostMapRelation.builder().userId(alice.getId()).hostId(aliceHost).sectionId(section.getId())
                .path("infra.md").fromLabel("lzi-prod").toLabel("compte prod").nature("dans").build());
        facts.save(HostMapFact.builder().userId(alice.getId()).hostId(aliceHost).sectionId(section.getId())
                .path("infra.md").heading("Clusters").lineNo(3)
                .text("- ne jamais redémarrer lzi-prod à la main").kind("PIEGE").build());
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private String graphPath(String ref) {
        return "/api/governance/hosts/" + ref + "/map/graph";
    }

    private String cardPath(String ref, String node) {
        return "/api/governance/hosts/" + ref + "/map/entities/" + node;
    }

    @Test
    @DisplayName("sans jeton : 401 ; le poste d'un autre compte : 404 (plan et fiche)")
    void guarded() throws Exception {
        String node = HostMapGraph.nodeId("lzi-prod");
        mockMvc.perform(get(graphPath(aliceHost.toString())).contextPath("/api")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(graphPath(aliceHost.toString())).contextPath("/api")
                .header("Authorization", "Bearer " + bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(get(cardPath(aliceHost.toString(), node)).contextPath("/api")
                .header("Authorization", "Bearer " + bobToken)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("le propriétaire lit le plan : nœuds fusionnés, niveau, pièges, liens")
    void ownerReadsGraph() throws Exception {
        String compte = HostMapGraph.nodeId("compte prod");
        mockMvc.perform(get(graphPath(aliceHost.toString())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(true))
                .andExpect(jsonPath("$.nodes.length()").value(2))
                .andExpect(jsonPath("$.nodes[0].label").value("compte prod"))
                .andExpect(jsonPath("$.nodes[0].depth").value(0))
                .andExpect(jsonPath("$.nodes[1].label").value("lzi-prod"))
                .andExpect(jsonPath("$.nodes[1].parentId").value(compte))
                .andExpect(jsonPath("$.nodes[1].traps").value(1))
                .andExpect(jsonPath("$.edges[0].nature").value("dans"));
    }

    @Test
    @DisplayName("la fiche : pièges en tête, relations ; un nœud inconnu : 404")
    void ownerReadsCard() throws Exception {
        mockMvc.perform(get(cardPath(aliceHost.toString(), HostMapGraph.nodeId("lzi-prod"))).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node.label").value("lzi-prod"))
                .andExpect(jsonPath("$.facts[0].kind").value("PIEGE"))
                .andExpect(jsonPath("$.relations[0].direction").value("out"))
                .andExpect(jsonPath("$.relations[0].otherLabel").value("compte prod"));
        mockMvc.perform(get(cardPath(aliceHost.toString(), "0000000000000000")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    @DisplayName("« Hébergé » n'a pas de carte : plan vide, non indexé")
    void hostedHasNothing() throws Exception {
        mockMvc.perform(get(graphPath("hosted")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(false))
                .andExpect(jsonPath("$.nodes.length()").value(0));
    }
}
