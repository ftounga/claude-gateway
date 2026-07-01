package fr.claudegateway.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import fr.claudegateway.email.EmailService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRole;
import fr.claudegateway.user.UserService;

/**
 * Tests unitaires de {@link PasswordResetService} : anti-énumération sur forgot, comptes
 * OAuth-only ignorés, application du reset (hachage + consommation), rejets de token.
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private PasswordResetTokenRepository tokenRepository;
    @Mock
    private SecureTokenGenerator tokenGenerator;
    @Mock
    private EmailService emailService;
    @Mock
    private UserService userService;
    @Mock
    private PasswordEncoder passwordEncoder;

    private PasswordResetService service() {
        return new PasswordResetService(
                tokenRepository, tokenGenerator, emailService, userService, passwordEncoder,
                "http://localhost:4200", Duration.ofHours(1));
    }

    private User localUser(String email) {
        return User.builder().id(UUID.randomUUID()).email(email).passwordHash("OLD-HASH")
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build();
    }

    @Test
    void requestResetCreatesTokenAndSendsMailForLocalUser() {
        when(userService.findByEmail("alice@example.com")).thenReturn(Optional.of(localUser("alice@example.com")));
        when(tokenGenerator.generate()).thenReturn("RAW");

        service().requestReset("Alice@Example.com");

        verify(tokenRepository).save(any(PasswordResetToken.class));
        verify(emailService).sendPasswordReset(
                eq("alice@example.com"), eq("http://localhost:4200/auth/reset?token=RAW"));
    }

    @Test
    void requestResetIsNoOpForUnknownEmail() {
        when(userService.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        service().requestReset("ghost@example.com");

        verify(tokenRepository, never()).save(any());
        verify(emailService, never()).sendPasswordReset(anyString(), anyString());
    }

    @Test
    void requestResetIsNoOpForOAuthOnlyAccount() {
        User oauth = User.builder().id(UUID.randomUUID()).email("eve@example.com").passwordHash(null)
                .provider(AuthProvider.GOOGLE).role(UserRole.USER).build();
        when(userService.findByEmail("eve@example.com")).thenReturn(Optional.of(oauth));

        service().requestReset("eve@example.com");

        verify(tokenRepository, never()).save(any());
        verify(emailService, never()).sendPasswordReset(anyString(), anyString());
    }

    @Test
    void resetUpdatesPasswordAndConsumesToken() {
        UUID userId = UUID.randomUUID();
        PasswordResetToken token = PasswordResetToken.builder()
                .userId(userId).token("VALID").expiresAt(OffsetDateTime.now().plusMinutes(30)).build();
        when(tokenRepository.findByToken("VALID")).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("new-password")).thenReturn("NEW-HASH");

        service().reset("VALID", "new-password");

        verify(userService).updatePassword(userId, "NEW-HASH");
        verify(tokenRepository).save(argThatUsed(token));
    }

    @Test
    void resetRejectsUnknownToken() {
        when(tokenRepository.findByToken("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().reset("NOPE", "new-password"))
                .isInstanceOf(InvalidPasswordResetTokenException.class);
        verify(userService, never()).updatePassword(any(), anyString());
    }

    @Test
    void resetRejectsExpiredToken() {
        PasswordResetToken token = PasswordResetToken.builder()
                .userId(UUID.randomUUID()).token("OLD").expiresAt(OffsetDateTime.now().minusMinutes(1)).build();
        when(tokenRepository.findByToken("OLD")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service().reset("OLD", "new-password"))
                .isInstanceOf(InvalidPasswordResetTokenException.class);
        verify(userService, never()).updatePassword(any(), anyString());
    }

    @Test
    void resetRejectsAlreadyConsumedToken() {
        PasswordResetToken token = PasswordResetToken.builder()
                .userId(UUID.randomUUID()).token("USED").expiresAt(OffsetDateTime.now().plusMinutes(30))
                .usedAt(OffsetDateTime.now().minusMinutes(1)).build();
        when(tokenRepository.findByToken("USED")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service().reset("USED", "new-password"))
                .isInstanceOf(InvalidPasswordResetTokenException.class);
        verify(userService, never()).updatePassword(any(), anyString());
    }

    private PasswordResetToken argThatUsed(PasswordResetToken token) {
        // Le même objet est ré-enregistré avec used_at renseigné (consommation).
        return org.mockito.ArgumentMatchers.argThat(saved ->
                saved == token && saved.getUsedAt() != null);
    }
}
