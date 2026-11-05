package fr.claudegateway.jarvis.state.capture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-247 / SF-247-15 lot 2 — conversations capturées, effacement, dépense en €, routes du relevé : sous user_id. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisKnowledgeApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private JarvisCaptureStore store;
    @Autowired private JwtService jwt;

    private User alice;
    private String aliceToken;
    private String bobToken;
    private UUID host;
    private UUID capture;

    @BeforeEach
    void setUp() {
        alice = user();
        aliceToken = jwt.generateToken(alice);
        bobToken = jwt.generateToken(user());
        host = hosts.save(RunnerHost.builder().userId(alice.getId()).name("CAGIP").rootName("dev").os("linux")
                .shell("posix").elevated(false).build()).getId();
        Workspace w = workspaces.save(Workspace.builder().userId(alice.getId()).name("SSO").hostId(host)
                .createdAt(OffsetDateTime.now()).build());
        OffsetDateTime at = OffsetDateTime.now().minusHours(3);
        store.save(alice.getId(), host, w.getId(), UUID.randomUUID(), "TEAMS", "Migration SSO", at, "LUE",
                BigDecimal.ZERO, List.of(new JarvisCaptureStore.NewLine("Rémi Dupont", true, at, false,
                        "Le ticket PROJ-123 passe en revue demain.")));
        capture = store.recentCaptures(alice.getId(), host, 1).get(0).id();
    }

    private User user() {
        return users.save(User.builder().email("know-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String token) {
        return r.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    @Test
    @DisplayName("Conversations du client, datées ; 404 pour un autre compte ; effacer une capture")
    void conversations() throws Exception {
        mvc.perform(as(get("/api/jarvis/state/clients/" + host + "/conversations"), aliceToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("Migration SSO"))
                .andExpect(jsonPath("$[0].platform").value("TEAMS"))
                .andExpect(jsonPath("$[0].lines[0].speaker").value("Rémi Dupont"));
        mvc.perform(as(get("/api/jarvis/state/clients/" + host + "/conversations"), bobToken))
                .andExpect(status().isNotFound());
        mvc.perform(as(delete("/api/jarvis/state/captures/" + capture), bobToken)).andExpect(status().isNotFound());
        mvc.perform(as(delete("/api/jarvis/state/captures/" + capture), aliceToken)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/jarvis/state/clients/" + host + "/conversations"), aliceToken))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Dépense en € avec plafonds propres ; routes du relevé sous user_id")
    void spendAndReleve() throws Exception {
        mvc.perform(as(get("/api/jarvis/state/spend"), aliceToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.captures.capEur").value(10.0)).andExpect(jsonPath("$.notes.capEur").value(15.0))
                .andExpect(jsonPath("$.captures.monthEur").value(0.0));
        mvc.perform(as(get("/api/jarvis/state/clients/" + host + "/releve"), aliceToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.probes.length()").value(0));
        mvc.perform(as(get("/api/jarvis/state/clients/" + host + "/releve"), bobToken))
                .andExpect(status().isNotFound());
        mvc.perform(as(post("/api/jarvis/state/clients/" + host + "/releve/setup"), bobToken))
                .andExpect(status().isNotFound());
        mvc.perform(as(post("/api/jarvis/state/clients/" + host + "/releve/setup"), aliceToken))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("host_offline"));
        mvc.perform(as(post("/api/jarvis/state/clients/" + host + "/releve/validate-all"), aliceToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
}
