package fr.claudegateway.auth;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;
import fr.claudegateway.user.UserService;

/**
 * Intégration OAuth2 <b>activée</b> (Google configuré via properties de test) :
 * le flux d'autorisation redirige vers Google, et la chaîne stateless JWT reste intacte
 * ({@code /api/me} sans token → 401, non-régression du {@code RestAuthenticationEntryPoint}).
 */
@SpringBootTest(properties = {
        "app.oauth2.google.client-id=test-client-id",
        "app.oauth2.google.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuth2LoginEnabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClientRegistrationRepository clientRegistrationRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void googleClientRegistrationIsAvailableWhenConfigured() {
        org.assertj.core.api.Assertions.assertThat(
                clientRegistrationRepository.findByRegistrationId("google")).isNotNull();
    }

    @Test
    void authorizationEndpointRedirectsToGoogle() throws Exception {
        mockMvc.perform(get("/api/oauth2/authorization/google").contextPath("/api"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("accounts.google.com")));
    }

    @Test
    void authorizationEndpointStoresAuthorizationRequestInCookie() throws Exception {
        // Handshake stateless : l'authorization request est portée par un cookie HttpOnly,
        // pas par une HttpSession (SF-01-08).
        mockMvc.perform(get("/api/oauth2/authorization/google").contextPath("/api"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Set-Cookie", allOf(
                        containsString("oauth2_auth_request="),
                        containsString("HttpOnly"))));
    }

    @Test
    void protectedEndpointStillReturns401WithoutToken() throws Exception {
        // Non-régression : oauth2Login activé ne doit pas transformer le 401 JSON en redirection login.
        mockMvc.perform(get("/api/me").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void findOrCreateGoogleUserCreatesVerifiedGoogleAccount() {
        userRepository.findByEmail("newgoogle@example.com").ifPresent(userRepository::delete);

        User created = userService.findOrCreateGoogleUser("newgoogle@example.com");

        org.assertj.core.api.Assertions.assertThat(created.getProvider()).isEqualTo(AuthProvider.GOOGLE);
        org.assertj.core.api.Assertions.assertThat(created.isEmailVerified()).isTrue();
        org.assertj.core.api.Assertions.assertThat(created.getRole()).isEqualTo(UserRole.USER);
        org.assertj.core.api.Assertions.assertThat(created.getPasswordHash()).isNull();
    }

    @Test
    void findOrCreateGoogleUserReusesExistingAccountByEmail() {
        User existing = userRepository.save(User.builder()
                .email("existing-federation@example.com").passwordHash("HASH")
                .emailVerified(false).provider(AuthProvider.LOCAL).role(UserRole.USER).build());

        User federated = userService.findOrCreateGoogleUser("existing-federation@example.com");

        // Fédération par e-mail : aucun doublon, on réutilise le compte existant.
        org.assertj.core.api.Assertions.assertThat(federated.getId()).isEqualTo(existing.getId());
    }
}
