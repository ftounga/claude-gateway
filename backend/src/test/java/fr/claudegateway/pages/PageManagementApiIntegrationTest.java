package fr.claudegateway.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Les pages d'un lieu : lister, versions, renommer, supprimer, exporter, purger (F-109 / SF-109-04). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PageManagementApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PageRepository pageRepository;
    @Autowired private PageService pageService;
    @Autowired private PageViewTicketService tickets;
    @Autowired private JwtService jwtService;

    private UUID alice;
    private String aliceToken;
    private String bobToken;
    private final UUID host = UUID.randomUUID();
    private UUID radarPage;
    private UUID forgePage;

    @BeforeEach
    void setUp() {
        pageRepository.deleteAll();
        User aliceUser = seed("alice-pages-mgmt@example.com");
        alice = aliceUser.getId();
        aliceToken = jwtService.generateToken(aliceUser);
        bobToken = jwtService.generateToken(seed("bob-pages-mgmt@example.com"));
        radarPage = pageService.publish(new PagePlace(alice, PageSpace.VIGIE, host, null), null, "Radar MFA", null,
                "<h1>v1</h1>", Map.of()).page().getId();
        pageService.publish(new PagePlace(alice, PageSpace.VIGIE, host, null), radarPage, "Radar MFA", null,
                "<h1>v2</h1>", Map.of());
        forgePage = pageService.publish(new PagePlace(alice, PageSpace.FORGE, host, UUID.randomUUID()), null, "Maquette",
                null, "<h1>forge</h1>", Map.of()).page().getId();
    }

    private User seed(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder().email(email)
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build()));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    @Test
    @DisplayName("CA1 — la liste d'un lieu : ce compte, ce poste, cet espace, avec un ticket de lecture")
    void listByPlace() throws Exception {
        mockMvc.perform(as(get("/api/pages").param("hostId", host.toString()).param("space", "vigie"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(radarPage.toString()))
                .andExpect(jsonPath("$[0].currentVersion").value(2))
                .andExpect(jsonPath("$[0].viewUrl", startsWith("/api/p/t1.")));
        mockMvc.perform(as(get("/api/pages").param("hostId", host.toString()).param("space", "FORGE"), bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(as(get("/api/pages").param("hostId", host.toString()).param("space", "AILLEURS"), aliceToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(get("/api/pages").param("space", "FORGE"), aliceToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("CA4 — versions décroissantes ; un ticket sur une version précise ; version inconnue 404")
    void versions() throws Exception {
        mockMvc.perform(as(get("/api/pages/" + radarPage + "/versions"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].version").value(1));
        String body = mockMvc.perform(as(get("/api/pages/" + radarPage).param("version", "1"), aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewUrl = body.replaceAll(".*\"viewUrl\":\"([^\"]+)\".*", "$1");
        String token = viewUrl.substring("/api/p/".length(), viewUrl.length() - 1);
        assertThat(tickets.read(token)).get().extracting(PageViewTicketService.Ticket::version).isEqualTo(1);

        mockMvc.perform(as(get("/api/pages/" + radarPage).param("version", "7"), aliceToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(get("/api/pages/" + radarPage + "/versions"), bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CA2 — renommer ; titre invalide 400 ; page d'autrui 404")
    void rename() throws Exception {
        mockMvc.perform(as(patch("/api/pages/" + radarPage), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Radar MFA — septembre\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Radar MFA — septembre"));
        mockMvc.perform(as(patch("/api/pages/" + radarPage), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(patch("/api/pages/" + radarPage), bobToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Volée\"}"))
                .andExpect(status().isNotFound());
        assertThat(pageService.require(alice, radarPage).getTitle()).isEqualTo("Radar MFA — septembre");
    }

    @Test
    @DisplayName("CA3 — supprimer : 204 ; Bob 404 et rien d'effacé")
    void deletePage() throws Exception {
        mockMvc.perform(as(delete("/api/pages/" + radarPage), bobToken)).andExpect(status().isNotFound());
        assertThat(pageService.require(alice, radarPage)).isNotNull();

        mockMvc.perform(as(delete("/api/pages/" + radarPage), aliceToken)).andExpect(status().isNoContent());
        mockMvc.perform(as(get("/api/pages/" + radarPage), aliceToken)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CA5 — l'export ZIP du lieu, en pièce jointe")
    void export() throws Exception {
        byte[] zip = mockMvc.perform(as(get("/api/pages/export").param("hostId", host.toString()).param("space", "VIGIE"),
                        aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", containsString("pages-vigie-")))
                .andReturn().getResponse().getContentAsByteArray();

        java.util.List<String> names = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (java.util.zip.ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        assertThat(names).containsExactly("radar-mfa/index.html");

        byte[] empty = mockMvc.perform(as(get("/api/pages/export").param("hostId", host.toString()).param("space", "VIGIE"),
                        bobToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(empty))) {
            assertThat(in.getNextEntry()).isNull();
        }
    }

    @Autowired private fr.claudegateway.account.AccountService accountService;
    @Autowired private fr.claudegateway.atelier.storage.WorkspaceStorage storage;

    @Test
    @DisplayName("CA7 — supprimer le compte efface les objets de ses pages, et leurs lignes")
    void accountDeletionErasesPages() {
        User carol = seed("carol-pages-mgmt@example.com");
        UUID page = pageService.publish(new PagePlace(carol.getId(), PageSpace.FORGE, host, null), null, "Carol", null,
                "<p>carol</p>", Map.of()).page().getId();
        assertThat(storage.listKeys(PageStore.PREFIX + carol.getId() + "/")).isNotEmpty();

        accountService.deleteAccount(carol.getId());

        assertThat(storage.listKeys(PageStore.PREFIX + carol.getId() + "/")).isEmpty();
        assertThat(pageRepository.findById(page)).isEmpty();
        assertThat(pageService.list(alice, host, PageSpace.VIGIE)).hasSize(1);
    }

    @Test
    @DisplayName("CA6 — purger un lieu : ce lieu seulement ; Bob ne purge rien chez Alice")
    void deletePlace() throws Exception {
        mockMvc.perform(as(delete("/api/pages").param("hostId", host.toString()).param("space", "VIGIE"), bobToken))
                .andExpect(status().isNoContent());
        assertThat(pageService.list(alice, host, PageSpace.VIGIE)).hasSize(1);

        mockMvc.perform(as(delete("/api/pages").param("hostId", host.toString()).param("space", "VIGIE"), aliceToken))
                .andExpect(status().isNoContent());
        assertThat(pageService.list(alice, host, PageSpace.VIGIE)).isEmpty();
        assertThat(pageService.require(alice, forgePage)).isNotNull();
    }
}
