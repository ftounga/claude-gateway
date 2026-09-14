package fr.claudegateway.mcp.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.PageRequest;
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
 * Accès MCP par <b>jeton personnel</b> (F-112 / SF-112-03), joué avec le client officiel du SDK :
 * un jeton {@code cgmcp_…} authentifie {@code /api/mcp}, l'appel est journalisé (sans contenu), et un
 * jeton révoqué est refusé.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpPersonalTokenAccessIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private McpPersonalTokenService tokenService;
    @Autowired
    private McpPersonalTokenRepository tokenRepository;
    @Autowired
    private McpJournalRepository journalRepository;

    private User user;

    @BeforeEach
    void setUp() {
        journalRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        user = userRepository.save(User.builder().email("owner@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    private McpSyncClient clientWithBearer(String bearer) {
        Consumer<java.net.http.HttpRequest.Builder> auth =
                b -> b.header("Authorization", "Bearer " + bearer);
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/api/mcp")
                .customizeRequest(auth)
                .build();
        return McpClient.sync(transport).build();
    }

    @Test
    void personalTokenAuthenticatesMcpAndIsJournaled() {
        String secret = tokenService.create(user.getId(), UserRole.USER, "CI",
                Set.of("compte:lire"), Set.of(), 30).secret();

        try (McpSyncClient client = clientWithBearer(secret)) {
            client.initialize();
            CallToolResult result = client.callTool(
                    CallToolRequest.builder().name("session_info").arguments(Map.of()).build());
            @SuppressWarnings("unchecked")
            Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
            assertThat(structured.get("user_id")).isEqualTo(user.getId().toString());
        }

        List<McpJournalEntry> journal =
                journalRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, 10));
        assertThat(journal).isNotEmpty();
        McpJournalEntry entry = journal.get(0);
        assertThat(entry.getTool()).isEqualTo("session_info");
        assertThat(entry.getAuthKind()).isEqualTo("PERSONAL");
        assertThat(entry.getResult()).isEqualTo("OK");
        assertThat(entry.getClient()).isEqualTo("CI");
        // Journal sans contenu : session_info n'a pas d'argument, le résumé est vide.
        assertThat(entry.getParamsSummary()).isEmpty();
    }

    @Test
    void revokedTokenIsRejected() {
        var created = tokenService.create(user.getId(), UserRole.USER, "CI",
                Set.of("compte:lire"), Set.of(), 30);
        tokenService.revoke(user.getId(), created.token().getId());

        try (McpSyncClient client = clientWithBearer(created.secret())) {
            assertThatThrownBy(client::initialize).isInstanceOf(Exception.class);
        }
    }
}
