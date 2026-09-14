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
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;

import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration des outils Postes et Forge (F-112 / SF-112-04), joués avec le client officiel
 * du SDK MCP contre le serveur embarqué : découverte, périmètres, accès poste par poste, isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PostesForgeToolsIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RunnerHostService hostService;
    @Autowired
    private com.nimbusds.jose.jwk.source.JWKSource<com.nimbusds.jose.proc.SecurityContext> jwkSource;
    @Autowired
    private McpResourceProperties resourceProperties;

    private McpAccessTokens accessTokens;
    private User userA;
    private User userB;
    private RunnerHost hostA;
    private RunnerHost hostB;

    @BeforeEach
    void setUp() {
        accessTokens = new McpAccessTokens(jwkSource);
        userA = userRepository.save(User.builder()
                .email("alice-" + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        userB = userRepository.save(User.builder()
                .email("bob-" + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        hostA = hostService.create(userA.getId(), "Poste d'Alice");
        hostB = hostService.create(userB.getId(), "Poste de Bob");
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

    @Test
    void toolsListExposesPostesAndForgeTools() {
        try (McpSyncClient client = client(token(userA, List.of("postes:lire")))) {
            client.initialize();
            ListToolsResult tools = client.listTools();
            List<String> names = tools.tools().stream().map(t -> t.name()).toList();
            assertThat(names).contains("postes_lister", "poste_detail", "projets_lister",
                    "projet_detail", "carte_lire", "gouvernance_etat", "poste_verifier",
                    "poste_mettre_a_jour_runner");
        }
    }

    @Test
    void postesListerReturnsOwnHostsOnly() {
        try (McpSyncClient client = client(token(userA, List.of("postes:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "postes_lister", Map.of());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> postes = (List<Map<String, Object>>) structured.get("postes");
            List<String> ids = postes.stream().map(p -> String.valueOf(p.get("id"))).toList();
            assertThat(ids).contains(hostA.getId().toString());
            assertThat(ids).doesNotContain(hostB.getId().toString());
        }
    }

    @Test
    void postesListerDeniedWithoutScope() {
        try (McpSyncClient client = client(token(userA, List.of("compte:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "postes_lister", Map.of());
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("postes:lire");
        }
    }

    @Test
    void posteDetailOnOwnHostSucceeds() {
        try (McpSyncClient client = client(token(userA, List.of("postes:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "poste_detail",
                    Map.of("host_id", hostA.getId().toString()));
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
            assertThat(structured.get("id")).isEqualTo(hostA.getId().toString());
            assertThat(structured).containsKey("projects");
        }
    }

    @Test
    void posteDetailOnOtherUserHostIsDenied() {
        try (McpSyncClient client = client(token(userA, List.of("postes:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "poste_detail",
                    Map.of("host_id", hostB.getId().toString()));
            assertThat(result.isError()).isTrue();
        }
    }

    @Test
    void posteDetailWithBadUuidIsError() {
        try (McpSyncClient client = client(token(userA, List.of("postes:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "poste_detail", Map.of("host_id", "not-a-uuid"));
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("host_id");
        }
    }

    @Test
    void mettreAJourRunnerRequiresAgirScope() {
        try (McpSyncClient client = client(token(userA, List.of("postes:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "poste_mettre_a_jour_runner",
                    Map.of("host_id", hostA.getId().toString()));
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("postes:agir");
        }
    }

    @Test
    void mettreAJourRunnerHasDestructiveAnnotation() {
        try (McpSyncClient client = client(token(userA, List.of("postes:lire")))) {
            client.initialize();
            var tool = client.listTools().tools().stream()
                    .filter(t -> "poste_mettre_a_jour_runner".equals(t.name())).findFirst().orElseThrow();
            assertThat(tool.annotations().destructiveHint()).isTrue();
            assertThat(tool.annotations().readOnlyHint()).isFalse();
        }
    }

    @Test
    void gouvernanceEtatSucceeds() {
        try (McpSyncClient client = client(token(userA, List.of("postes:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "gouvernance_etat", Map.of());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
            assertThat(structured).containsKey("postes");
        }
    }

    @Test
    void projetsListerSucceeds() {
        try (McpSyncClient client = client(token(userA, List.of("postes:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "projets_lister", Map.of());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        }
    }

    private CallToolResult call(McpSyncClient client, String name, Map<String, Object> args) {
        return client.callTool(CallToolRequest.builder().name(name).arguments(args).build());
    }

    private String text(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        result.content().forEach(c -> sb.append(c.toString()));
        return sb.toString();
    }
}
