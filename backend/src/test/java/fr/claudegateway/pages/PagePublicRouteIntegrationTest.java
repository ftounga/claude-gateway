package fr.claudegateway.pages;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>La seule route ouverte sans compte</b> (F-109 / SF-109-01) : elle sert ce que désigne un ticket signé,
 * sous la même politique — et la chaîne de filtres n'ouvre <b>rien d'autre</b>.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PagePublicRouteIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PageRepository pageRepository;
    @Autowired private PageService pageService;
    @Autowired private PageViewTicketService tickets;
    @Autowired private PageLimits limits;
    @Value("${app.jwt.secret}") private String jwtSecret;

    private UUID alice;
    private UUID bob;
    private UUID alicePage;
    private UUID bobPage;

    @BeforeEach
    void setUp() {
        pageRepository.deleteAll();
        alice = seedUser("alice-pages-public@example.com");
        bob = seedUser("bob-pages-public@example.com");
        alicePage = pageService.publish(new PagePlace(alice, PageSpace.VIGIE, UUID.randomUUID(), null), null,
                "Compte rendu", null, "<h1>Alice</h1><img src=\"logo.svg\">",
                Map.of("logo.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes())).page().getId();
        bobPage = pageService.publish(new PagePlace(bob, PageSpace.FORGE, null, null), null, "Bob", null,
                "<h1>Bob</h1>", Map.of()).page().getId();
    }

    private UUID seedUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder().email(email)
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build())).getId();
    }

    private String url(String token) {
        return "/api/p/" + token + "/";
    }

    @Test
    @DisplayName("CA7 — un ticket valide sert la page SANS JWT, sous la politique")
    void validTicket() throws Exception {
        mockMvc.perform(get(url(tickets.issue(alice, alicePage, 0))).contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<h1>Alice</h1>")))
                .andExpect(header().string("Content-Security-Policy", PageContentPolicy.CSP))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
    }

    @Test
    @DisplayName("CA7 — sans barre finale : redirection relative vers la barre finale")
    void redirectsToTrailingSlash() throws Exception {
        String token = tickets.issue(alice, alicePage, 0);
        mockMvc.perform(get("/api/p/" + token).contextPath("/api"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", token + "/"));
    }

    @Test
    @DisplayName("CA7 — expiré, falsifié, tronqué ou inconnu : 404, sous la même politique")
    void invalidTickets() throws Exception {
        PageViewTicketService past = new PageViewTicketService(jwtSecret, limits,
                Clock.fixed(java.time.Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC));
        String expired = past.issue(alice, alicePage, 0);
        String valid = tickets.issue(alice, alicePage, 0);
        for (String token : new String[] { expired, valid.substring(0, valid.length() - 3) + "xyz",
                "t1.abc.def", "nimportequoi", UUID.randomUUID().toString() }) {
            mockMvc.perform(get(url(token)).contextPath("/api"))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string("Content-Security-Policy", PageContentPolicy.CSP))
                    .andExpect(content().string(containsString("introuvable")));
        }
    }

    @Test
    @DisplayName("CA6 — un ticket signé pour Alice sur la page de Bob n'ouvre rien")
    void ticketBoundToItsAccount() throws Exception {
        mockMvc.perform(get(url(tickets.issue(alice, bobPage, 0))).contextPath("/api"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CA10 — une pièce jointe se sert sous le ticket, avec son type et la politique ; « .. » est refusé")
    void attachment() throws Exception {
        String token = tickets.issue(alice, alicePage, 0);
        mockMvc.perform(get(url(token) + "logo.svg").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/svg+xml"))
                .andExpect(header().string("Content-Security-Policy", PageContentPolicy.CSP));
        mockMvc.perform(get(url(token) + "absent.svg").contextPath("/api"))
                .andExpect(status().isNotFound());
        // « .. » est refusé dès le pare-feu HTTP de Spring Security (400) ; un nom hors liste, par le service.
        mockMvc.perform(get(url(token) + "..").contextPath("/api"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get(url(token) + "run.exe").contextPath("/api"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CA9 — un ticket présenté comme Bearer n'authentifie RIEN")
    void ticketIsNotABearer() throws Exception {
        String token = tickets.issue(alice, alicePage, 0);
        mockMvc.perform(get("/api/pages/" + alicePage).contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me").contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CA8 — NON-RÉGRESSION : la chaîne n'ouvre que GET /p/** ; tout le reste exige un JWT")
    void onlyThePublicReadIsOpen() throws Exception {
        for (String path : new String[] { "/api/pages/" + alicePage, "/api/pages/" + alicePage + "/content",
                "/api/workspaces", "/api/me", "/api/billing/subscription", "/api/runner-hosts" }) {
            mockMvc.perform(get(path).contextPath("/api")).andExpect(status().isUnauthorized());
        }
        String token = tickets.issue(alice, alicePage, 0);
        mockMvc.perform(post(url(token)).contextPath("/api")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(url(token)).contextPath("/api")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(url(token)).contextPath("/api")).andExpect(status().isUnauthorized());
        // « /pages » ne commence pas par « /p/ » : le préfixe ouvert ne s'étend pas par ressemblance.
        mockMvc.perform(get("/api/pages").contextPath("/api")).andExpect(status().isUnauthorized());
    }
}
