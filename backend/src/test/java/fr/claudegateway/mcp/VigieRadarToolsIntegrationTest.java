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

import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration des outils Vigie et Radar (F-112 / SF-112-06) avec le client officiel du SDK :
 * découverte, périmètres, accès poste par poste, garde Vigie, isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class VigieRadarToolsIntegrationTest {

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
                .email("a-" + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        userB = userRepository.save(User.builder()
                .email("b-" + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        hostA = hostService.create(userA.getId(), "Poste A");
        hostB = hostService.create(userB.getId(), "Poste B");
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
    void exposesNineRadarTools() {
        try (McpSyncClient client = client(token(userA, List.of("radar:lire", "radar:ecrire")))) {
            client.initialize();
            List<String> names = client.listTools().tools().stream().map(t -> t.name()).toList();
            assertThat(names).contains("radar_synchroniser", "radar_resume", "radar_sujets",
                    "radar_sujet", "radar_donner_nouvelle", "radar_clore_sujet", "radar_lier_projet",
                    "radar_preparer_relance", "radar_couverture");
        }
    }

    @Test
    void radarSujetsDeniedWithoutReadScope() {
        try (McpSyncClient client = client(token(userA, List.of("compte:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "radar_sujets",
                    Map.of("host_id", hostA.getId().toString()));
            assertThat(result.isError()).isTrue();
        }
    }

    @Test
    void radarDonnerNouvelleDeniedWithoutWriteScope() {
        try (McpSyncClient client = client(token(userA, List.of("radar:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "radar_donner_nouvelle",
                    Map.of("host_id", hostA.getId().toString(), "text", "une nouvelle"));
            assertThat(result.isError()).isTrue();
        }
    }

    @Test
    void ownHostNotInVigieIsError() {
        try (McpSyncClient client = client(token(userA, List.of("radar:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "radar_sujets",
                    Map.of("host_id", hostA.getId().toString()));
            // Poste possédé mais pas activé dans la Vigie : erreur claire, pas de fuite.
            assertThat(result.isError()).isTrue();
        }
    }

    @Test
    void otherUserHostIsDenied() {
        try (McpSyncClient client = client(token(userA, List.of("radar:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "radar_sujets",
                    Map.of("host_id", hostB.getId().toString()));
            assertThat(result.isError()).isTrue();
        }
    }

    @Test
    void badHostIdIsError() {
        try (McpSyncClient client = client(token(userA, List.of("radar:lire")))) {
            client.initialize();
            CallToolResult result = call(client, "radar_resume", Map.of("host_id", "not-a-uuid"));
            assertThat(result.isError()).isTrue();
            assertThat(result.content().toString()).contains("host_id");
        }
    }
}
