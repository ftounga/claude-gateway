package fr.claudegateway.mcp.token;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * API « IA connectées » (F-112 / SF-112-03) : création (secret rendu une seule fois), listage sans
 * secret, révocation, validations (expiration, périmètre, poste) et isolation {@code user_id}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpConnectionsApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private McpPersonalTokenRepository tokenRepository;
    @Autowired
    private RunnerHostRepository hostRepository;
    @Autowired
    private JwtService jwtService;

    private String tokenA;
    private User userA;
    private String tokenB;
    private User userB;

    @BeforeEach
    void setUp() {
        tokenRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();
        userA = userRepository.save(User.builder().email("a@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        tokenA = jwtService.generateToken(userA);
        userB = userRepository.save(User.builder().email("b@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        tokenB = jwtService.generateToken(userB);
    }

    private String create(String body, String jwt) throws Exception {
        return mockMvc.perform(post("/api/mcp-connections/tokens").contextPath("/api")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void createReturnsSecretOnceThenNeverAgain() throws Exception {
        mockMvc.perform(post("/api/mcp-connections/tokens").contextPath("/api")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CI\",\"scopes\":[\"compte:lire\"],\"expiresInDays\":30}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret", Matchers.startsWith("cgmcp_")))
                .andExpect(jsonPath("$.token.prefix", Matchers.startsWith("cgmcp_")))
                .andExpect(jsonPath("$.token.revoked").value(false));

        // La liste ne renvoie jamais le secret (le champ n'existe pas dans TokenResponse).
        mockMvc.perform(get("/api/mcp-connections/tokens").contextPath("/api")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].secret").doesNotExist())
                .andExpect(jsonPath("$[0].name").value("CI"));
    }

    @Test
    void expirationIsMandatoryAndBounded() throws Exception {
        mockMvc.perform(post("/api/mcp-connections/tokens").contextPath("/api")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"scopes\":[\"compte:lire\"]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/mcp-connections/tokens").contextPath("/api")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"scopes\":[\"compte:lire\"],\"expiresInDays\":91}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminScopeIsReservedForAdmin() throws Exception {
        mockMvc.perform(post("/api/mcp-connections/tokens").contextPath("/api")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"scopes\":[\"admin\"],\"expiresInDays\":30}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void hostOfAnotherUserIsRejected() throws Exception {
        RunnerHost hostOfB = hostRepository.save(RunnerHost.builder()
                .userId(userB.getId()).name("Poste de B").build());
        mockMvc.perform(post("/api/mcp-connections/tokens").contextPath("/api")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"scopes\":[\"postes:lire\"],\"expiresInDays\":30,"
                                + "\"hostIds\":[\"" + hostOfB.getId() + "\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tokensAreIsolatedPerUser() throws Exception {
        String created = create(
                "{\"name\":\"CI\",\"scopes\":[\"compte:lire\"],\"expiresInDays\":30}", tokenA);
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.token.id");

        // B ne voit pas le jeton de A.
        mockMvc.perform(get("/api/mcp-connections/tokens").contextPath("/api")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(0)));

        // B ne peut pas révoquer le jeton de A (introuvable de son point de vue).
        mockMvc.perform(delete("/api/mcp-connections/tokens/" + id).contextPath("/api")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // A le révoque.
        mockMvc.perform(delete("/api/mcp-connections/tokens/" + id).contextPath("/api")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/mcp-connections/tokens").contextPath("/api")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$[0].revoked").value(true));
    }
}
