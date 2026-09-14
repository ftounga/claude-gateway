package fr.claudegateway.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
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
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests de conformité du serveur MCP (F-112 / SF-112-01), joués avec le <b>client officiel du SDK
 * MCP</b> (transport Streamable HTTP) contre le vrai serveur embarqué : négociation de version,
 * découverte des outils, appel d'outil, isolation de l'identité et refus sans jeton.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpServerConformanceIntegrationTest {

    private static final List<String> KNOWN_PROTOCOL_VERSIONS =
            List.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");

    @LocalServerPort
    private int port;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;

    private String tokenA;
    private User userA;
    private String tokenB;
    private User userB;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        userA = userRepository.save(User.builder()
                .email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        tokenA = jwtService.generateToken(userA);
        userB = userRepository.save(User.builder()
                .email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        tokenB = jwtService.generateToken(userB);
    }

    private McpSyncClient clientWithToken(String token) {
        Consumer<java.net.http.HttpRequest.Builder> auth = token == null
                ? b -> { }
                : b -> b.header("Authorization", "Bearer " + token);
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/api/mcp")
                .customizeRequest(auth)
                .build();
        return McpClient.sync(transport).build();
    }

    @Test
    void initializeNegotiatesSupportedProtocol() {
        try (McpSyncClient client = clientWithToken(tokenA)) {
            InitializeResult result = client.initialize();
            assertThat(result.protocolVersion()).isIn(KNOWN_PROTOCOL_VERSIONS);
            assertThat(result.serverInfo().name()).isEqualTo("claude-gateway");
        }
    }

    @Test
    void toolsListExposesSessionInfoWithAnnotations() {
        try (McpSyncClient client = clientWithToken(tokenA)) {
            client.initialize();
            ListToolsResult tools = client.listTools();
            Tool sessionInfo = tools.tools().stream()
                    .filter(t -> "session_info".equals(t.name()))
                    .findFirst()
                    .orElseThrow();
            assertThat(sessionInfo.annotations()).isNotNull();
            assertThat(sessionInfo.annotations().readOnlyHint()).isTrue();
            assertThat(sessionInfo.inputSchema()).isNotNull();
        }
    }

    @Test
    void sessionInfoReturnsIdentityOfPresentedToken() {
        try (McpSyncClient client = clientWithToken(tokenA)) {
            client.initialize();
            CallToolResult result = client.callTool(
                    CallToolRequest.builder().name("session_info").arguments(Map.of()).build());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
            assertThat(structured.get("user_id")).isEqualTo(userA.getId().toString());
            assertThat(structured.get("email")).isEqualTo("alice@example.com");
        }
    }

    @Test
    void sessionInfoIsolatesIdentityBetweenTokens() {
        try (McpSyncClient client = clientWithToken(tokenB)) {
            client.initialize();
            CallToolResult result = client.callTool(
                    CallToolRequest.builder().name("session_info").arguments(Map.of()).build());
            @SuppressWarnings("unchecked")
            Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
            assertThat(structured.get("user_id")).isEqualTo(userB.getId().toString());
            assertThat(structured.get("user_id")).isNotEqualTo(userA.getId().toString());
        }
    }

    @Test
    void missingTokenIsRejected() {
        try (McpSyncClient client = clientWithToken(null)) {
            assertThatThrownBy(client::initialize).isInstanceOf(Exception.class);
        }
    }
}
