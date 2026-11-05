package fr.claudegateway.jarvis.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.companion.JarvisCompanionScope;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-248 / SF-248-08 — {@code GET /api/jarvis/cards?keys=} : clés citées par Opus, registre du compte, ≤ 6, isolation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCardKeysApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private JarvisStateRegistry registry;
    @Autowired private JwtService jwt;

    private String aliceToken;
    private String bobToken;
    private UUID bobSubject;
    private UUID aliceSubject;

    @BeforeEach
    void setUp() {
        User alice = user();
        User bob = user();
        aliceToken = jwt.generateToken(alice);
        bobToken = jwt.generateToken(bob);
        UUID cagip = host(alice, "CAGIP");
        UUID bobHost = host(bob, "Bob");
        aliceSubject = workspaces.save(Workspace.builder().userId(alice.getId()).name("Migration").hostId(cagip)
                .projectPath("m").executionTarget(WorkspaceExecutionTarget.RUNNER).build()).getId();
        bobSubject = workspaces.save(Workspace.builder().userId(bob.getId()).name("Bob").hostId(bobHost)
                .projectPath("b").executionTarget(WorkspaceExecutionTarget.RUNNER).build()).getId();
        OffsetDateTime at = OffsetDateTime.now().minusHours(3);
        registry.record(alice.getId(), List.of(obs(cagip, "TICKET", "JIRA:PROJ-123", "status", "En revue", at),
                obs(cagip, "MR", "GITLAB:projet!42", "state", "opened", at)));
        registry.record(bob.getId(), List.of(obs(bobHost, "TICKET", "JIRA:PROJ-123", "status", "Secret de Bob", at)));
    }

    private static JarvisStateRegistry.Observation obs(UUID host, String kind, String key, String attr, String value,
            OffsetDateTime at) {
        return new JarvisStateRegistry.Observation(host, null, kind, key, attr, value, at, "TOUR", null, "HAUTE");
    }

    private UUID host(User owner, String name) {
        return hosts.save(RunnerHost.builder().userId(owner.getId()).name(name).rootName("dev").os("linux")
                .shell("posix").elevated(false).build()).getId();
    }

    private User user() {
        return users.save(User.builder().email("cards-k-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build());
    }

    private ResultActions keys(String token, String query) throws Exception {
        return mvc.perform(get("/api/jarvis/cards?" + query).contextPath("/api").header("Authorization", "Bearer "
                + token));
    }

    @Test
    @DisplayName("les clés citées déjà au registre reviennent en pancartes (ciblées) ; une clé inconnue, rien")
    void citedKeys() throws Exception {
        keys(aliceToken, "keys=PROJ-123,projet!42,PROJ-999&subjectId=" + aliceSubject).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("PROJ-123"))
                .andExpect(jsonPath("$[0].status.word").value("En revue"))
                .andExpect(jsonPath("$[0].targeted").value(true))
                .andExpect(jsonPath("$[1].type").value("MR"));
    }

    @Test
    @DisplayName("isolation : Bob ne lit que son registre ; le sujet de Bob ne borne rien pour Alice ; 400 sans clé")
    void isolation() throws Exception {
        String bobs = keys(bobToken, "keys=PROJ-123,projet!42").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andReturn().getResponse().getContentAsString();
        assertThat(bobs).contains("Secret de Bob");
        String alices = keys(aliceToken, "keys=PROJ-123&subjectId=" + bobSubject).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(alices).contains("En revue").doesNotContain("Secret de Bob");
        keys(aliceToken, "keys=bonjour,%2342").andExpect(status().isBadRequest());
        keys(aliceToken, "").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("6 clés au plus ; portée compagnon : GET /jarvis/cards ouvert, rien d'autre")
    void boundedAndScoped() {
        assertThat(JarvisCardKeyLookup.accepted(List.of("AA-1", "BB-2", "CC-3", "DD-4", "EE-5", "FF-6", "GG-7", "AA-1")))
                .hasSize(6);
        assertThat(JarvisCardKeyLookup.candidates("proj-12")).containsExactly("JIRA:PROJ-12");
        assertThat(JarvisCardKeyLookup.candidates("groupe/projet!42")).containsExactly("GITLAB:groupe/projet!42",
                "GITHUB:groupe/projet!42");
        assertThat(JarvisCompanionScope.allows("GET", "/jarvis/cards")).isTrue();
        assertThat(JarvisCompanionScope.allows("POST", "/jarvis/cards")).isFalse();
    }
}
