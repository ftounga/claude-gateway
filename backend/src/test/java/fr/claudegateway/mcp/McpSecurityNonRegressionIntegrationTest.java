package fr.claudegateway.mcp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Non-régression de la chaîne de sécurité MCP (F-112 / SF-112-01) : la chaîne dédiée {@code /mcp}
 * exige un jeton et n'ouvre rien d'autre ; les routes existantes gardent leur authentification.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpSecurityNonRegressionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;

    private String token;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        User user = userRepository.save(User.builder()
                .email("carol@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        token = jwtService.generateToken(user);
    }

    @Test
    void mcpEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/mcp").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("application/json", "text/event-stream")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void existingRouteStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/me").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void existingRouteRemainsReachableWithJwt() throws Exception {
        mockMvc.perform(get("/api/me").contextPath("/api")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
