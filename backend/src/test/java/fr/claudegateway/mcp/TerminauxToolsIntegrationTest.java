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

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration des outils Terminaux (F-112 / SF-112-05) avec le client officiel du SDK MCP :
 * découverte, périmètre, droit d'accès, lancement de tour + suivi, autorisations listées sans être
 * accordées, isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TerminauxToolsIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceService workspaceService;
    @Autowired
    private com.nimbusds.jose.jwk.source.JWKSource<com.nimbusds.jose.proc.SecurityContext> jwkSource;
    @Autowired
    private McpResourceProperties resourceProperties;

    private McpAccessTokens accessTokens;
    private User admin;
    private User plain;
    private Workspace adminWorkspace;
    private Workspace plainWorkspace;

    @BeforeEach
    void setUp() {
        accessTokens = new McpAccessTokens(jwkSource);
        admin = userRepository.save(User.builder()
                .email("admin-" + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        plain = userRepository.save(User.builder()
                .email("plain-" + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        adminWorkspace = workspaceService.createLocal(admin.getId(), "Projet admin");
        plainWorkspace = workspaceService.createLocal(plain.getId(), "Projet plain");
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
    void exposesTerminalToolsAndNoGrantTool() {
        try (McpSyncClient client = client(token(admin, List.of("terminaux:ecrire")))) {
            client.initialize();
            List<String> names = client.listTools().tools().stream().map(t -> t.name()).toList();
            assertThat(names).contains("terminaux_lister", "terminal_ecrire", "tour_suivre",
                    "tour_preciser", "tour_interrompre", "autorisations_en_attente");
            // Garde §6.1 : aucune IA n'accorde une autorisation — aucun outil ne le permet.
            assertThat(names).noneMatch(n -> n.contains("confirm") || n.contains("accorder")
                    || n.contains("autoriser") || n.contains("approuver") || n.contains("grant"));
        }
    }

    @Test
    void terminalEcrireDeniedWithoutScope() {
        try (McpSyncClient client = client(token(admin, List.of("compte:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "terminal_ecrire",
                    Map.of("project_id", adminWorkspace.getId().toString(), "message", "salut"));
            assertThat(result.isError()).isTrue();
        }
    }

    @Test
    void terminalEcrireDeniedWithoutEntitlement() {
        try (McpSyncClient client = client(token(plain, List.of("terminaux:ecrire")))) {
            client.initialize();
            CallToolResult result = call(client, "terminal_ecrire",
                    Map.of("project_id", plainWorkspace.getId().toString(), "message", "salut"));
            assertThat(result.isError()).isTrue();
        }
    }

    @Test
    void terminalEcrireLaunchesTurnAndTourSuivreReads() {
        try (McpSyncClient client = client(token(admin, List.of("terminaux:ecrire")))) {
            client.initialize();
            CallToolResult launch = call(client, "terminal_ecrire",
                    Map.of("project_id", adminWorkspace.getId().toString(), "message", "bonjour"));
            assertThat(launch.isError()).isNotEqualTo(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> launched = (Map<String, Object>) launch.structuredContent();
            assertThat(launched.get("turn_id")).isNotNull();
            assertThat(launched.get("steered")).isEqualTo(false);

            CallToolResult follow = call(client, "tour_suivre",
                    Map.of("project_id", adminWorkspace.getId().toString(), "cursor", 0));
            assertThat(follow.isError()).isNotEqualTo(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> state = (Map<String, Object>) follow.structuredContent();
            assertThat(state).containsKeys("live", "cursor", "events");
        }
    }

    @Test
    void autorisationsEnAttenteListsWithoutGranting() {
        try (McpSyncClient client = client(token(admin, List.of("terminaux:ecrire")))) {
            client.initialize();
            CallToolResult result = call(client, "autorisations_en_attente", Map.of());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
            assertThat(structured).containsKey("pending");
            assertThat(structured.get("note").toString()).contains("n'accorde jamais");
        }
    }

    @Test
    void tourSuivreOnOtherUserProjectIsError() {
        try (McpSyncClient client = client(token(admin, List.of("terminaux:ecrire")))) {
            client.initialize();
            CallToolResult result = call(client, "tour_suivre",
                    Map.of("project_id", plainWorkspace.getId().toString()));
            assertThat(result.isError()).isTrue();
        }
    }

    @Test
    void tourPreciserWithoutLiveTurnIsError() {
        try (McpSyncClient client = client(token(admin, List.of("terminaux:ecrire")))) {
            client.initialize();
            CallToolResult result = call(client, "tour_preciser",
                    Map.of("project_id", adminWorkspace.getId().toString(), "message", "précision"));
            assertThat(result.isError()).isTrue();
        }
    }
}
