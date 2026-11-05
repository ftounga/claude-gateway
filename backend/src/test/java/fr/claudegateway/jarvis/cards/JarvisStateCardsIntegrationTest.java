package fr.claudegateway.jarvis.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.state.JarvisStateBlock;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateTool;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-248 / SF-248-07 — les faits du registre F-247 : fiches de l'outil {@code etat} (lecture ciblée), du bloc préparé
 * (en mémoire), plein écran par index ; isolation à deux utilisateurs et deux clients.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisStateCardsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private JarvisStateRegistry registry;
    @Autowired private JarvisStateTool tool;
    @Autowired private JarvisStateBlock block;
    @Autowired private JwtService jwt;

    private User alice;
    private String aliceToken;
    private String bobToken;
    private UUID cagip;
    private UUID edenred;
    private UUID bobHost;

    @BeforeEach
    void setUp() {
        alice = user();
        User bob = user();
        aliceToken = jwt.generateToken(alice);
        bobToken = jwt.generateToken(bob);
        cagip = host(alice, "CAGIP");
        edenred = host(alice, "Edenred");
        bobHost = host(bob, "Bob");
        OffsetDateTime at = OffsetDateTime.now().minusHours(2);
        registry.record(alice.getId(), List.of(
                obs(cagip, "TICKET", "JIRA:PROJ-123", "status", "En cours", at.minusHours(20)),
                obs(cagip, "TICKET", "JIRA:PROJ-123", "title", "Migration SSO", at),
                obs(cagip, "TICKET", "JIRA:PROJ-123", "assignee", "Rémi", at),
                obs(cagip, "MR", "GITLAB:projet!42", "state", "merged", at),
                obs(cagip, "MR", "GITLAB:projet!42", "url", "https://gitlab.example.com/p/-/merge_requests/42", at),
                obs(edenred, "DEPLOYMENT", "K8S:prod/api", "health", "Degraded", at)));
        registry.record(alice.getId(), List.of(obs(cagip, "TICKET", "JIRA:PROJ-123", "status", "En revue", at)));
        registry.record(bob.getId(), List.of(obs(bobHost, "TICKET", "JIRA:BOB-1", "status", "Secret de Bob", at)));
    }

    @AfterEach
    void close() {
        JarvisCardCatalog.close();
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
        return users.save(User.builder().email("state-cards-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build());
    }

    @Test
    @DisplayName("outil etat (lecture ciblée) : une pancarte par objet, ciblée, gabarits ticket / MR / déploiement, url relevée")
    void toolCardsAreTargeted() {
        JarvisCardCatalog catalog = JarvisCardCatalog.open();
        tool.read(alice.getId(), null, new ObjectMapper().createObjectNode(), false);
        List<JarvisCard> cards = catalog.cards();
        assertThat(cards).hasSize(3).allMatch(JarvisCard::targeted);
        JarvisCard ticket = cards.stream().filter(c -> c.type().equals("TICKET")).findFirst().orElseThrow();
        assertThat(ticket.title()).isEqualTo("PROJ-123 — Migration SSO");
        assertThat(ticket.status().word()).isEqualTo("En revue");
        assertThat(ticket.group()).isEqualTo("CAGIP");
        assertThat(ticket.aliases()).contains("PROJ-123");
        assertThat(ticket.source()).isEqualTo("tour");
        JarvisCard mr = cards.stream().filter(c -> c.type().equals("MR")).findFirst().orElseThrow();
        assertThat(mr.status().tone()).isEqualTo("ok");
        assertThat(mr.link()).isEqualTo("https://gitlab.example.com/p/-/merge_requests/42");
        JarvisCard deploy = cards.stream().filter(c -> c.type().equals("DEPLOIEMENT")).findFirst().orElseThrow();
        assertThat(deploy.status().tone()).isEqualTo("alerte");
        assertThat(deploy.group()).isEqualTo("Edenred");
        assertThat(cards.toString()).doesNotContain("Secret de Bob");
    }

    @Test
    @DisplayName("Z12 : le bloc préparé rend ses faits en mémoire ; « Poste … éteint depuis » ; rien pour Bob")
    void preparedFacts() {
        block.compose(alice.getId());
        JarvisStateBlock.Prepared prepared = block.compose(alice.getId());
        List<JarvisStateBlock.Line> lines = prepared.clients().stream().flatMap(c -> c.lines().stream()).toList();
        List<JarvisStateRegistry.Fact> facts = block.factsFor(alice.getId(), lines);
        assertThat(JarvisStateCards.fromFacts(facts, Map.of(cagip, "CAGIP", edenred, "Edenred"))).hasSize(3);
        assertThat(block.factsFor(UUID.randomUUID(), lines)).isEmpty();
        assertThat(JarvisStateCards.offlineNote("CAGIP", OffsetDateTime.parse("2026-11-05T17:05:00Z")))
                .isEqualTo("Poste CAGIP éteint depuis 18 h 05");
    }

    @Test
    @DisplayName("plein écran : par index sous le compte, valeur précédente ; Bob ⇒ 404 ; un client de Bob ⇒ 404")
    void detail() throws Exception {
        String id = JarvisStateCards.id(cagip, "JIRA:PROJ-123");
        mvc.perform(get("/api/jarvis/cards/" + id).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.card.title").value("PROJ-123 — Migration SSO"))
                .andExpect(jsonPath("$.previous.value").value("En cours"))
                .andExpect(jsonPath("$.fields[?(@.label == 'Assigné')].value").value("Rémi"))
                .andExpect(jsonPath("$.fields[?(@.label == 'Client')].value").value("CAGIP"));
        mvc.perform(get("/api/jarvis/cards/" + id).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        String bobs = JarvisStateCards.id(bobHost, "JIRA:BOB-1");
        mvc.perform(get("/api/jarvis/cards/" + bobs).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
        assertThat(JarvisStateCards.parse(id.substring("fait:etat:".length())).orElseThrow().getValue())
                .isEqualTo("JIRA:PROJ-123");
        assertThat(JarvisStateCards.parse("pas.un-id")).isEmpty();
    }
}
