package fr.claudegateway.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration des outils Pages / Compte / Administration (F-112 / SF-112-07) avec le client
 * officiel du SDK : découverte, périmètres, double garde admin (périmètre + rôle), isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PagesCompteAdminToolsIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private com.nimbusds.jose.jwk.source.JWKSource<com.nimbusds.jose.proc.SecurityContext> jwkSource;
    @Autowired
    private McpResourceProperties resourceProperties;

    private McpAccessTokens accessTokens;
    private User plain;
    private User admin;

    @BeforeEach
    void setUp() {
        accessTokens = new McpAccessTokens(jwkSource);
        plain = userRepository.save(User.builder()
                .email("plain-" + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        admin = userRepository.save(User.builder()
                .email("admin-" + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private String token(User user, List<String> scopes) {
        return accessTokens.forUser(user.getId(), resourceProperties.resource(), scopes);
    }

    private McpSyncClient client(String token) {
        Consumer<java.net.http.HttpRequest.Builder> auth =
                b -> b.header("Authorization", "Bearer " + token);
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/api/mcp")
                .customizeRequest(auth)
                .build();
        return McpClient.sync(transport).build();
    }

    private CallToolResult call(McpSyncClient client, String name, Map<String, Object> args) {
        return client.callTool(CallToolRequest.builder().name(name).arguments(args).build());
    }

    @Test
    void exposesTenTools() {
        try (McpSyncClient client = client(token(admin, List.of("pages", "courriel", "compte:lire", "admin")))) {
            client.initialize();
            List<String> names = client.listTools().tools().stream().map(t -> t.name()).toList();
            assertThat(names).contains("page_publier", "pages_lister", "page_lire",
                    "courriel_m_envoyer", "compte_consommation", "compte_abonnement",
                    "admin_utilisateurs", "admin_codes_acces_emettre", "admin_quota_crediter",
                    "admin_sante");
        }
    }

    @Test
    void pagesListerDeniedWithoutScope() {
        try (McpSyncClient client = client(token(plain, List.of("compte:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "pages_lister", Map.of());
            assertThat(result.isError()).isTrue();
        }
    }

    @Test
    void compteConsommationSucceeds() {
        try (McpSyncClient client = client(token(plain, List.of("compte:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "compte_consommation", Map.of());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
            assertThat(structured).containsKey("consommation");
        }
    }

    @Test
    void adminToolDeniedForNonAdminEvenWithAdminScope() {
        try (McpSyncClient client = client(token(plain, List.of("admin")))) {
            client.initialize();
            CallToolResult result = call(client, "admin_utilisateurs", Map.of());
            assertThat(result.isError()).isTrue();
            assertThat(result.content().toString()).contains("ADMIN");
        }
    }

    @Test
    void adminToolSucceedsForAdmin() {
        try (McpSyncClient client = client(token(admin, List.of("admin")))) {
            client.initialize();
            CallToolResult result = call(client, "admin_utilisateurs", Map.of());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
            assertThat(structured).containsKey("utilisateurs");
        }
    }

    @Test
    void adminSanteDeniedWithoutAdminScope() {
        try (McpSyncClient client = client(token(admin, List.of("compte:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "admin_sante", Map.of());
            assertThat(result.isError()).isTrue();
        }
    }

    @Test
    void pagePublierThenPageLireMarksContentUntrusted() {
        try (McpSyncClient client = client(token(plain, List.of("pages")))) {
            client.initialize();
            CallToolResult published = call(client, "page_publier", Map.of(
                    "title", "Ma page", "html", "<h1>Bonjour</h1>"));
            assertThat(published.isError()).isNotEqualTo(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> pub = (Map<String, Object>) published.structuredContent();
            String pageId = String.valueOf(pub.get("page_id"));
            assertThat(pageId).isNotBlank();

            CallToolResult read = call(client, "page_lire", Map.of("page_id", pageId));
            assertThat(read.isError()).isNotEqualTo(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> state = (Map<String, Object>) read.structuredContent();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) state.get("data");
            assertThat(data.get("untrusted")).isEqualTo(true);
        }
    }
}
