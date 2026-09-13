package fr.claudegateway.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Le partage d'une page : créer, ouvrir sans compte, compter, révoquer, expirer, journal (F-109 / SF-109-05). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PageShareApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PageRepository pageRepository;
    @Autowired private PageShareRepository shareRepository;
    @Autowired private PageEventRepository eventRepository;
    @Autowired private PageService pageService;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper mapper;

    private UUID alice;
    private String aliceToken;
    private String bobToken;
    private UUID page;

    @BeforeEach
    void setUp() {
        pageRepository.deleteAll();
        User aliceUser = seed("alice-pages-share@example.com");
        alice = aliceUser.getId();
        aliceToken = jwtService.generateToken(aliceUser);
        bobToken = jwtService.generateToken(seed("bob-pages-share@example.com"));
        page = pageService.publish(new PagePlace(alice, PageSpace.VIGIE, UUID.randomUUID(), null), null, "Compte rendu",
                null, "<h1>v1</h1><img src=\"logo.svg\">", Map.of("logo.svg", "<svg/>".getBytes())).page().getId();
        pageService.publish(new PagePlace(alice, PageSpace.VIGIE, null, null), page, "Compte rendu", null,
                "<h1>v2</h1><img src=\"logo.svg\">", Map.of("logo.svg", "<svg/>".getBytes()));
    }

    private User seed(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder().email(email)
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build()));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    private JsonNode share(String body) throws Exception {
        String json = mockMvc.perform(as(post("/api/pages/" + page + "/shares"), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json);
    }

    private static String tokenOf(JsonNode created) {
        return created.get("url").asText().substring("/p/".length());
    }

    @Test
    @DisplayName("CA1/CA2 — un lien : 43 caractères, empreinte seule en base ; ouvert SANS compte sous la politique, version courante")
    void createAndOpenWithoutAccount() throws Exception {
        JsonNode created = share("{}");
        String token = tokenOf(created);

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
        PageShare stored = shareRepository.findById(UUID.fromString(created.get("id").asText())).orElseThrow();
        assertThat(stored.getTokenHash()).hasSize(64).isNotEqualTo(token).doesNotContain(token);
        assertThat(Duration.between(stored.getCreatedAt(), stored.getExpiresAt())).isEqualTo(Duration.ofDays(7));

        mockMvc.perform(get("/api/p/" + token + "/").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<h1>v2</h1>")))
                .andExpect(header().string("Content-Security-Policy", PageContentPolicy.CSP))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mockMvc.perform(get("/api/p/" + token + "/logo.svg").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", PageContentPolicy.CSP));
    }

    @Test
    @DisplayName("CA4 — chaque ouverture du HTML compte et se journalise ; une pièce jointe non")
    void openingsAreCounted() throws Exception {
        String token = tokenOf(share("{\"expiresInDays\":30}"));

        mockMvc.perform(get("/api/p/" + token + "/").contextPath("/api")).andExpect(status().isOk());
        mockMvc.perform(get("/api/p/" + token + "/").contextPath("/api")).andExpect(status().isOk());
        mockMvc.perform(get("/api/p/" + token + "/logo.svg").contextPath("/api")).andExpect(status().isOk());

        mockMvc.perform(as(get("/api/pages/" + page + "/shares"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].openCount").value(2))
                .andExpect(jsonPath("$[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$[0].lastOpenedAt").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(token))));
    }

    @Test
    @DisplayName("CA3 — révoqué : 404 immédiat, sous la politique ; révoquer deux fois est sans effet")
    void revokedIs404() throws Exception {
        JsonNode created = share("{}");
        String token = tokenOf(created);

        mockMvc.perform(as(delete("/api/pages/" + page + "/shares/" + created.get("id").asText()), aliceToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/api/pages/" + page + "/shares/" + created.get("id").asText()), aliceToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/p/" + token + "/").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Security-Policy", PageContentPolicy.CSP));
        mockMvc.perform(as(get("/api/pages/" + page + "/shares"), aliceToken))
                .andExpect(jsonPath("$[0].state").value("REVOKED"));
    }

    @Autowired private PageService pages;

    @Test
    @DisplayName("CA3 — expiré : 404 ; inconnu : 404")
    void expiredIs404() throws Exception {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofDays(3)), ZoneOffset.UTC);
        PageShareService pastService = new PageShareService(pages, shareRepository, eventRepository, past);
        String token = pastService.create(alice, page, 1).token();

        mockMvc.perform(get("/api/p/" + token + "/").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Security-Policy", PageContentPolicy.CSP));
        mockMvc.perform(get("/api/p/" + "A".repeat(43) + "/").contextPath("/api"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CA5 — expiration de 1 à 90 jours ; hors borne 400")
    void expirationBounds() throws Exception {
        for (String body : new String[] { "{\"expiresInDays\":0}", "{\"expiresInDays\":91}" }) {
            mockMvc.perform(as(post("/api/pages/" + page + "/shares"), aliceToken)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(share("{\"expiresInDays\":90}").get("expiresAt").asText()).isNotBlank();
        assertThat(share("{\"expiresInDays\":1}").get("url").asText()).startsWith("/p/");
    }

    @Test
    @DisplayName("CA6 — Bob ne crée, ne liste, ne révoque ni ne lit le journal d'une page d'Alice")
    void isolation() throws Exception {
        JsonNode created = share("{}");

        mockMvc.perform(as(post("/api/pages/" + page + "/shares"), bobToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(get("/api/pages/" + page + "/shares"), bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(as(delete("/api/pages/" + page + "/shares/" + created.get("id").asText()), bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(get("/api/pages/" + page + "/journal"), bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/p/" + tokenOf(created) + "/").contextPath("/api")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("CA7 — le journal : créée, version, partagée, ouverte, révoquée — le plus récent d'abord")
    void journal() throws Exception {
        JsonNode created = share("{}");
        mockMvc.perform(get("/api/p/" + tokenOf(created) + "/").contextPath("/api")).andExpect(status().isOk());
        mockMvc.perform(as(delete("/api/pages/" + page + "/shares/" + created.get("id").asText()), aliceToken))
                .andExpect(status().isNoContent());

        String json = mockMvc.perform(as(get("/api/pages/" + page + "/journal"), aliceToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> kinds = new java.util.ArrayList<>();
        mapper.readTree(json).forEach(entry -> kinds.add(entry.get("kind").asText()));
        assertThat(kinds).containsExactly("REVOKED", "OPENED", "SHARED", "VERSION", "CREATED");
    }

    @Test
    @DisplayName("CA8 — non-régression : seul GET /p/** est ouvert ; un jeton de partage n'est pas un Bearer")
    void onlyGetIsOpen() throws Exception {
        String token = tokenOf(share("{}"));

        mockMvc.perform(post("/api/p/" + token + "/").contextPath("/api")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/p/" + token + "/").contextPath("/api")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/p/" + token + "/").contextPath("/api")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/pages/" + page + "/shares").contextPath("/api")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me").contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("supprimer la page : ses liens répondent 404")
    void deletedPageLinksAre404() throws Exception {
        String token = tokenOf(share("{}"));

        pageService.delete(alice, page);

        mockMvc.perform(get("/api/p/" + token + "/").contextPath("/api")).andExpect(status().isNotFound());
        assertThat(shareRepository.findAll()).noneMatch(share -> share.getPageId().equals(page));
    }
}
