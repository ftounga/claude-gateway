package fr.claudegateway.pages.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.pages.PagePlace;
import fr.claudegateway.pages.PageRepository;
import fr.claudegateway.pages.PageService;
import fr.claudegateway.pages.PageSpace;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Le PDF d'une page : route, isolation, lot, échecs du moteur (F-184 / SF-184-02). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PagePdfApiIntegrationTest {

    private static final byte[] PDF = "%PDF-1.7 faux".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PageRepository pageRepository;
    @Autowired private PageService pageService;
    @Autowired private JwtService jwtService;

    @MockitoBean private PagePdfRenderer renderer;
    @MockitoBean private ExternalResourceFetcher fetcher;

    private String aliceToken;
    private String bobToken;
    private UUID page;

    @BeforeEach
    void setUp() {
        pageRepository.deleteAll();
        User alice = seed("alice-pages-pdf@example.com");
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(seed("bob-pages-pdf@example.com"));
        String html = "<!doctype html><html><head>"
                + "<script src=\"https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js\"></script>"
                + "<script src=\"https://evil.example/x.js\"></script></head><body><h1>Radar</h1>"
                + "<img src=\"capture.png\"><pre class=\"mermaid\">flowchart LR\nA-->B</pre></body></html>";
        page = pageService.publish(new PagePlace(alice.getId(), PageSpace.FORGE, null, null), null,
                "Radar MFA", null, html, Map.of("capture.png", PNG)).page().getId();
        when(renderer.isAvailable()).thenReturn(true);
        when(fetcher.fetch(any())).thenAnswer(inv -> Optional.of(new ExternalResourceFetcher.Fetched(
                inv.getArgument(0), "text/javascript", "/*chart*/".getBytes(StandardCharsets.UTF_8))));
    }

    private User seed(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder().email(email)
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build()));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    @Test
    @DisplayName("CA1/CA3 — 200 application/pdf, nom de fichier, lot complet, manquants relayés")
    @SuppressWarnings("unchecked")
    void printsThePage() throws Exception {
        when(renderer.print(anyString(), any())).thenReturn(
                new PagePdfRenderer.Printed(PDF, "https://cdn.jsdelivr.net/npm/plugin.js"));

        mockMvc.perform(as(get("/api/pages/" + page + "/pdf"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("radar-mfa-v1.pdf")))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Cg-Missing-Resources", "https://cdn.jsdelivr.net/npm/plugin.js"))
                .andExpect(content().bytes(PDF));

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<PagePdfRenderer.Resource>> lot = ArgumentCaptor.forClass(List.class);
        verify(renderer).print(html.capture(), lot.capture());
        List<String> urls = lot.getValue().stream().map(PagePdfRenderer.Resource::url).toList();
        assertThat(urls).contains(
                PagePdfRenderer.ORIGIN + "/capture.png",
                PagePdfRenderer.ORIGIN + "/api/pages/lib/mermaid-11.15.0.min.js",
                "https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js");
        assertThat(urls).noneMatch(u -> u.contains("evil.example"));
        // Le HTML servi (runtime Mermaid injecté), pas le HTML stocké.
        assertThat(html.getValue()).contains("/api/pages/lib/mermaid-11.15.0.min.js");
    }

    @Test
    @DisplayName("CA2 — la page d'un autre compte : 404, et le moteur n'est jamais appelé")
    void isolation() throws Exception {
        mockMvc.perform(as(get("/api/pages/" + page + "/pdf"), bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(get("/api/pages/" + page + "/pdf").param("version", "7"), aliceToken))
                .andExpect(status().isNotFound());
        verify(renderer, never()).print(anyString(), any());
    }

    @Test
    @DisplayName("non authentifié : 401")
    void anonymous() throws Exception {
        mockMvc.perform(get("/api/pages/" + page + "/pdf").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CA7 — moteur indisponible : 503 ; refus du moteur : 422 avec sa raison")
    void engineFailures() throws Exception {
        when(renderer.print(anyString(), any()))
                .thenThrow(new PagePdfUnavailableException("Le PDF n'a pas pu être produit pour le moment, réessayez."));
        mockMvc.perform(as(get("/api/pages/" + page + "/pdf"), aliceToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("page_pdf_unavailable"));

        doThrow(new PagePdfRejectedException("Page trop lourde : 9000000 octets (8 Mo au plus)."))
                .when(renderer).print(anyString(), any());
        mockMvc.perform(as(get("/api/pages/" + page + "/pdf"), aliceToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("Page trop lourde")));
    }
}
