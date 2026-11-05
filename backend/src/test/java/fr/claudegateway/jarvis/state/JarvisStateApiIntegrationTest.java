package fr.claudegateway.jarvis.state;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
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

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-247 / SF-247-15 — l'API du volet « Ce que je sais » : activé d'office, couper purge, effacer, isolation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisStateApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private JarvisStateRegistry registry;
    @Autowired private JwtService jwt;

    private User alice;
    private String aliceToken;
    private String bobToken;
    private UUID host;

    @BeforeEach
    void setUp() {
        alice = user();
        aliceToken = jwt.generateToken(alice);
        bobToken = jwt.generateToken(user());
        host = hosts.save(RunnerHost.builder().userId(alice.getId()).name("CAGIP").rootName("dev").os("linux")
                .shell("posix").elevated(false).build()).getId();
        registry.record(alice.getId(), List.of(new JarvisStateRegistry.Observation(host, null, "TICKET",
                "JIRA:PROJ-1", "status", "En revue", OffsetDateTime.now().minusHours(1), "TOUR", null, "HAUTE")));
    }

    private User user() {
        return users.save(User.builder().email("state-api-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String token) {
        return r.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    @Test
    @DisplayName("activé d'office ; faits listés ; couper purge ; 404 pour un autre compte ; 400 hors bornes")
    void settingsAndFacts() throws Exception {
        mvc.perform(as(get("/api/jarvis/state/clients"), aliceToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("CAGIP")).andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].facts").value(1));
        mvc.perform(as(get("/api/jarvis/state/clients/" + host + "/facts"), aliceToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].objectKey").value("JIRA:PROJ-1"))
                .andExpect(jsonPath("$[0].value").value("En revue"));
        mvc.perform(as(get("/api/jarvis/state/clients/" + host + "/facts"), bobToken))
                .andExpect(status().isNotFound());
        mvc.perform(as(put("/api/jarvis/state/clients/" + host), bobToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isNotFound());
        mvc.perform(as(put("/api/jarvis/state/clients/" + host), aliceToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"factRetentionDays\":0}")).andExpect(status().isBadRequest());
        mvc.perform(as(put("/api/jarvis/state/clients/" + host), aliceToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"disabledFamilies\":[\"PIGEON\"]}")).andExpect(status().isBadRequest());
        mvc.perform(as(put("/api/jarvis/state/clients/" + host), aliceToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"disabledFamilies\":[\"mr\"],\"factRetentionDays\":14}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.disabledFamilies[0]").value("MR"))
                .andExpect(jsonPath("$.factRetentionDays").value(14));
        mvc.perform(as(put("/api/jarvis/state/clients/" + host), aliceToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.facts").value(0));
    }

    @Test
    @DisplayName("effacer un fait (pas celui d'un autre) ; effacer tout")
    void erase() throws Exception {
        UUID id = registry.recent(alice.getId(), host, 1).get(0).id();
        mvc.perform(as(delete("/api/jarvis/state/facts/" + id), bobToken)).andExpect(status().isNotFound());
        mvc.perform(as(delete("/api/jarvis/state/facts/" + id), aliceToken)).andExpect(status().isNoContent());
        registry.record(alice.getId(), List.of(new JarvisStateRegistry.Observation(host, null, "TICKET",
                "JIRA:PROJ-2", "status", "Fait", OffsetDateTime.now(), "TOUR", null, "HAUTE")));
        mvc.perform(as(delete("/api/jarvis/state/clients/" + host + "/facts"), bobToken))
                .andExpect(status().isNotFound());
        mvc.perform(as(delete("/api/jarvis/state/clients/" + host + "/facts"), aliceToken))
                .andExpect(status().isNoContent());
        mvc.perform(as(get("/api/jarvis/state/clients/" + host + "/facts"), aliceToken))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
