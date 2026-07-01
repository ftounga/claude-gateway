package fr.claudegateway.auth;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration du socle sécurité sur {@code GET /api/me} : accès public refusé,
 * accès authentifié, rejet des tokens expirés / d'utilisateurs inconnus, et isolation {@code user_id}.
 *
 * <p>Note : MockMvc s'exécute sous le context-path du conteneur. On le fixe explicitement à
 * {@code /api} via {@code .contextPath("/api")} afin d'exercer l'URL publique réelle {@code /api/me}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    private User alice;

    @BeforeEach
    void seedUsers() {
        userRepository.deleteAll();
        alice = userRepository.save(User.builder()
                .email("alice@example.com")
                .emailVerified(true)
                .provider(AuthProvider.LOCAL)
                .role(UserRole.USER)
                .build());
        // Second utilisateur pour prouver l'isolation : ne doit jamais fuiter dans /api/me d'Alice.
        userRepository.save(User.builder()
                .email("bob@example.com")
                .emailVerified(false)
                .provider(AuthProvider.GOOGLE)
                .role(UserRole.ADMIN)
                .build());
    }

    @Test
    void returnsUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(me(null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("unauthorized")))
                .andExpect(jsonPath("$.message", notNullValue()));
    }

    @Test
    void returnsCurrentUserWithValidToken() throws Exception {
        String token = jwtService.generateToken(alice);

        mockMvc.perform(me(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(alice.getId().toString())))
                .andExpect(jsonPath("$.email", is("alice@example.com")))
                .andExpect(jsonPath("$.emailVerified", is(true)))
                .andExpect(jsonPath("$.provider", is("LOCAL")))
                .andExpect(jsonPath("$.role", is("USER")));
    }

    @Test
    void returnsUnauthorizedWithExpiredToken() throws Exception {
        JwtService expiringService = new JwtService(jwtSecret, Duration.ofSeconds(-60));
        String expiredToken = expiringService.generateToken(alice);

        mockMvc.perform(me(expiredToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsUnauthorizedWhenUserUnknown() throws Exception {
        User ghost = User.builder()
                .id(UUID.randomUUID())
                .email("ghost@example.com")
                .provider(AuthProvider.LOCAL)
                .role(UserRole.USER)
                .build();
        String token = jwtService.generateToken(ghost);

        mockMvc.perform(me(token))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder me(String token) {
        MockHttpServletRequestBuilder request = get("/api/me").contextPath("/api");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request;
    }
}
