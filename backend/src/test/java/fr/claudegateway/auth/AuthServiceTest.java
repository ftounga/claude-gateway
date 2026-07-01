package fr.claudegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import fr.claudegateway.auth.dto.AuthResponse;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRole;
import fr.claudegateway.user.UserService;

/**
 * Tests unitaires de {@link AuthService} : normalisation email, unicité, hachage/vérif BCrypt,
 * émission JWT et messages d'erreur anti-énumération. Les collaborateurs sont mockés.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserService userService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    private User localUser(String email, String hash) {
        return User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .passwordHash(hash)
                .emailVerified(false)
                .provider(AuthProvider.LOCAL)
                .role(UserRole.USER)
                .build();
    }

    @Test
    void registerNormalizesEmailHashesPasswordAndCreatesUser() {
        when(userService.emailExists("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret-password")).thenReturn("HASH");
        when(userService.createLocalUser("alice@example.com", "HASH"))
                .thenReturn(localUser("alice@example.com", "HASH"));

        MeResponse response = authService.register("  Alice@Example.COM ", "secret-password");

        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.provider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(response.emailVerified()).isFalse();
        verify(userService).createLocalUser("alice@example.com", "HASH");
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userService.emailExists("bob@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register("Bob@example.com", "secret-password"))
                .isInstanceOf(EmailAlreadyUsedException.class);

        verify(passwordEncoder, never()).encode(any());
        verify(userService, never()).createLocalUser(any(), any());
    }

    @Test
    void loginReturnsTokenForValidCredentials() {
        User user = localUser("carol@example.com", "HASH");
        when(userService.findByEmail("carol@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("good", "HASH")).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("jwt-token");

        AuthResponse response = authService.login("Carol@Example.com", "good");

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.user().email()).isEqualTo("carol@example.com");
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = localUser("dave@example.com", "HASH");
        when(userService.findByEmail("dave@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "HASH")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("dave@example.com", "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void loginRejectsUnknownEmail() {
        when(userService.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("ghost@example.com", "whatever"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginRejectsOAuthOnlyAccountWithoutPasswordHash() {
        User oauthUser = User.builder()
                .id(UUID.randomUUID())
                .email("eve@example.com")
                .passwordHash(null)
                .provider(AuthProvider.GOOGLE)
                .role(UserRole.USER)
                .build();
        when(userService.findByEmail("eve@example.com")).thenReturn(Optional.of(oauthUser));

        assertThatThrownBy(() -> authService.login("eve@example.com", "whatever"))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(passwordEncoder, never()).matches(eq("whatever"), any());
    }
}
