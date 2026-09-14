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
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Test <b>de bout en bout</b> du serveur MCP (F-112 / SF-112-08) avec le client officiel du SDK : un
 * client se connecte (jeton d'accès, comme après OAuth), découvre outils/ressources/prompts, lit les
 * postes, écrit dans un terminal, suit le tour, et voit une autorisation en attente <b>sans pouvoir
 * l'accorder</b>. Le flux OAuth navigateur lui-même est couvert par les tests de SF-112-02.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpEndToEndIntegrationTest {

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
    private User user;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        accessTokens = new McpAccessTokens(jwkSource);
        user = userRepository.save(User.builder()
                .email("e2e-" + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        workspace = workspaceService.createLocal(user.getId(), "Projet e2e");
    }

    private McpSyncClient client() {
        String token = accessTokens.forUser(user.getId(), resourceProperties.resource(),
                List.of("postes:lire", "postes:agir", "terminaux:ecrire", "radar:lire",
                        "radar:ecrire", "pages", "courriel", "compte:lire", "admin"));
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
    void fullJourney() {
        try (McpSyncClient client = client()) {
            client.initialize();

            // Découverte : outils, ressources, prompts.
            List<String> tools = client.listTools().tools().stream().map(t -> t.name()).toList();
            assertThat(tools).contains("postes_lister", "terminal_ecrire", "tour_suivre",
                    "autorisations_en_attente");
            assertThat(tools).noneMatch(n -> n.contains("confirm") || n.contains("accorder")
                    || n.contains("autoriser") || n.contains("grant"));

            List<String> resources = client.listResources().resources().stream()
                    .map(r -> r.uri()).toList();
            assertThat(resources).contains("cg://guide", "cg://postes");

            List<String> prompts = client.listPrompts().prompts().stream().map(p -> p.name()).toList();
            assertThat(prompts).contains("atelier_test_poste", "etat_clients_matin",
                    "preparer_reponse_manager");

            // Lecture par référence : le guide et les postes.
            ReadResourceResult guide = client.readResource(new ReadResourceRequest("cg://guide"));
            assertThat(text(guide)).contains("claude mcp add");
            ReadResourceResult postesRes = client.readResource(new ReadResourceRequest("cg://postes"));
            assertThat(text(postesRes)).contains("postes");

            // Un prompt s'obtient et porte un message.
            GetPromptResult prompt = client.getPrompt(
                    new GetPromptRequest("atelier_test_poste", Map.of()));
            assertThat(prompt.messages()).isNotEmpty();

            // Lecture des postes.
            CallToolResult postes = call(client, "postes_lister", Map.of());
            assertThat(postes.isError()).isNotEqualTo(Boolean.TRUE);

            // Écriture dans un terminal → un tour (tâche).
            CallToolResult launch = call(client, "terminal_ecrire",
                    Map.of("project_id", workspace.getId().toString(), "message", "test e2e"));
            assertThat(launch.isError()).isNotEqualTo(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> launched = (Map<String, Object>) launch.structuredContent();
            assertThat(launched.get("turn_id")).isNotNull();

            // Suivi du tour par curseur.
            CallToolResult follow = call(client, "tour_suivre",
                    Map.of("project_id", workspace.getId().toString(), "cursor", 0));
            assertThat(follow.isError()).isNotEqualTo(Boolean.TRUE);

            // Autorisations en attente : listées, jamais accordées.
            CallToolResult pending = call(client, "autorisations_en_attente", Map.of());
            assertThat(pending.isError()).isNotEqualTo(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> pendingState = (Map<String, Object>) pending.structuredContent();
            assertThat(pendingState.get("note").toString()).contains("n'accorde jamais");
        }
    }

    private CallToolResult call(McpSyncClient client, String name, Map<String, Object> args) {
        return client.callTool(CallToolRequest.builder().name(name).arguments(args).build());
    }

    private String text(ReadResourceResult result) {
        StringBuilder sb = new StringBuilder();
        result.contents().forEach(c -> {
            if (c instanceof TextResourceContents t) {
                sb.append(t.text());
            }
        });
        return sb.toString();
    }
}
