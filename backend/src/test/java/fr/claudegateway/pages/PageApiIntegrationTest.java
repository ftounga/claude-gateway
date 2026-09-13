package fr.claudegateway.pages;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Lire une page de son compte, sous la politique de §3 (F-109 / SF-109-01). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PageApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PageRepository pageRepository;
    @Autowired private PageService pageService;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private UUID alicePage;

    @BeforeEach
    void setUp() {
        pageRepository.deleteAll();
        User alice = seedUser("alice-pages-api@example.com");
        User bob = seedUser("bob-pages-api@example.com");
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
        UUID page = pageService.publish(new PagePlace(alice.getId(), PageSpace.FORGE, null, null), null,
                "Radar de l'été", "Le point.", "<h1>v1</h1>", Map.of()).page().getId();
        pageService.publish(new PagePlace(alice.getId(), PageSpace.FORGE, null, null), page,
                "Radar de l'été", "Le point.", "<h1>v2</h1>", Map.of());
        alicePage = page;
    }

    private User seedUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder().email(email)
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build()));
    }

    @Test
    @DisplayName("GET /pages/{id} — métadonnées et adresse de lecture par ticket, jamais le JWT")
    void metadata() throws Exception {
        mockMvc.perform(get("/api/pages/" + alicePage).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Radar de l'été"))
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.space").value("FORGE"))
                .andExpect(jsonPath("$.viewUrl", startsWith("/api/p/t1.")))
                .andExpect(jsonPath("$.viewUrl", not(containsString(aliceToken))));
    }

    @Test
    @DisplayName("CA4/CA5 — le contenu est servi sous la politique d'origine opaque")
    void contentUnderPolicy() throws Exception {
        mockMvc.perform(get("/api/pages/" + alicePage + "/content").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(content().string("<h1>v2</h1>"))
                .andExpect(header().string("Content-Type", startsWith("text/html")))
                .andExpect(header().string("Content-Security-Policy", PageContentPolicy.CSP))
                .andExpect(header().string("Content-Security-Policy", containsString("sandbox allow-scripts allow-popups")))
                .andExpect(header().string("Content-Security-Policy", containsString("connect-src 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("form-action 'none'")))
                .andExpect(header().string("Content-Security-Policy", not(containsString("allow-same-origin"))))
                .andExpect(header().string("Content-Security-Policy", not(containsString("allow-forms"))))
                .andExpect(header().string("Content-Security-Policy", not(containsString("allow-top-navigation"))))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test
    @DisplayName("CA5 — une version précise, et le téléchargement en pièce jointe")
    void versionAndDownload() throws Exception {
        mockMvc.perform(get("/api/pages/" + alicePage + "/content").param("version", "1").param("download", "true")
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(content().string("<h1>v1</h1>"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("radar-de-l-ete-v1.html")))
                .andExpect(header().string("Content-Security-Policy", PageContentPolicy.CSP));
    }

    @Test
    void unknownVersionIs404() throws Exception {
        mockMvc.perform(get("/api/pages/" + alicePage + "/content").param("version", "9").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CA6 — la page d'Alice est introuvable pour Bob, métadonnées comme contenu")
    void isolation() throws Exception {
        mockMvc.perform(get("/api/pages/" + alicePage).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/pages/" + alicePage + "/content").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/pages/" + UUID.randomUUID()).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }
}
