package fr.claudegateway.auth;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;

/**
 * Tests d'intégration des endpoints publics {@code /api/auth/register} et {@code /api/auth/login}.
 * MockMvc s'exécute sous le context-path {@code /api} (comme le socle).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(String path, String body) {
        return post(path).contextPath("/api").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void registerCreatesLocalUnverifiedUser() throws Exception {
        mockMvc.perform(json("/api/auth/register",
                        "{\"email\":\"NewUser@Example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email", is("newuser@example.com")))
                .andExpect(jsonPath("$.emailVerified", is(false)))
                .andExpect(jsonPath("$.provider", is("LOCAL")))
                .andExpect(jsonPath("$.role", is("USER")))
                .andExpect(jsonPath("$.id", notNullValue()));

        User stored = userRepository.findByEmail("newuser@example.com").orElseThrow();
        // Le hash BCrypt est persisté, jamais le mot de passe en clair.
        org.assertj.core.api.Assertions.assertThat(stored.getPasswordHash())
                .isNotBlank()
                .doesNotContain("password123")
                .startsWith("$2");
    }

    @Test
    void registerRejectsDuplicateEmail() throws Exception {
        mockMvc.perform(json("/api/auth/register",
                        "{\"email\":\"dup@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(json("/api/auth/register",
                        "{\"email\":\"dup@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("email_already_used")));
    }

    @Test
    void registerRejectsInvalidEmail() throws Exception {
        mockMvc.perform(json("/api/auth/register",
                        "{\"email\":\"not-an-email\",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void registerRejectsShortPassword() throws Exception {
        mockMvc.perform(json("/api/auth/register",
                        "{\"email\":\"short@example.com\",\"password\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void loginReturnsUsableJwt() throws Exception {
        mockMvc.perform(json("/api/auth/register",
                        "{\"email\":\"login@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());

        String response = mockMvc.perform(json("/api/auth/login",
                        "{\"email\":\"Login@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.user.email", is("login@example.com")))
                .andReturn().getResponse().getContentAsString();

        String token = com.jayway.jsonpath.JsonPath.read(response, "$.accessToken");
        // Le JWT émis est exploitable sur un endpoint protégé.
        mockMvc.perform(get("/api/me").contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("login@example.com")));
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        mockMvc.perform(json("/api/auth/register",
                        "{\"email\":\"wrongpw@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(json("/api/auth/login",
                        "{\"email\":\"wrongpw@example.com\",\"password\":\"bad-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("invalid_credentials")));
    }

    @Test
    void loginRejectsUnknownEmailWithSameGenericError() throws Exception {
        mockMvc.perform(json("/api/auth/login",
                        "{\"email\":\"nobody@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("invalid_credentials")));
    }
}
