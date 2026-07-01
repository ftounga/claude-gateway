package fr.claudegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRole;
import fr.claudegateway.user.UserService;

/**
 * Tests unitaires du {@link OAuth2LoginSuccessHandler} : fédération + émission JWT + redirection
 * en fragment, et cas de l'e-mail absent.
 */
@ExtendWith(MockitoExtension.class)
class OAuth2LoginSuccessHandlerTest {

    @Mock
    private UserService userService;
    @Mock
    private JwtService jwtService;

    private OAuth2LoginSuccessHandler handler() {
        return new OAuth2LoginSuccessHandler(userService, jwtService, "http://localhost:4200");
    }

    private OAuth2AuthenticationToken tokenWithAttributes(Map<String, Object> attributes, String nameKey) {
        OAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, nameKey);
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }

    @Test
    void federatesUserEmitsJwtAndRedirectsWithFragment() throws Exception {
        User user = User.builder().id(UUID.randomUUID()).email("alice@example.com")
                .emailVerified(true).provider(AuthProvider.GOOGLE).role(UserRole.USER).build();
        when(userService.findOrCreateGoogleUser("alice@example.com")).thenReturn(user);
        when(jwtService.generateToken(user)).thenReturn("jwt-token");

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler().onAuthenticationSuccess(new MockHttpServletRequest(), response,
                tokenWithAttributes(Map.of("email", "Alice@Example.com", "sub", "123"), "email"));

        verify(userService).findOrCreateGoogleUser("alice@example.com");
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:4200/auth/callback#token=jwt-token");
    }

    @Test
    void redirectsWithErrorWhenEmailMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler().onAuthenticationSuccess(new MockHttpServletRequest(), response,
                tokenWithAttributes(Map.of("sub", "123"), "sub"));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:4200/auth/callback#error=email_unavailable");
        verify(userService, never()).findOrCreateGoogleUser(any());
        verify(jwtService, never()).generateToken(any());
    }
}
