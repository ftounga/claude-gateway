package fr.claudegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRole;
import fr.claudegateway.user.UserService;

/**
 * Tests unitaires de {@link ProfileService} : mise à jour d'e-mail (changement + re-vérif,
 * no-op si identique, conflit), et déconnexion « toutes sessions ».
 */
@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private UserService userService;
    @Mock
    private EmailVerificationService emailVerificationService;

    private ProfileService service() {
        return new ProfileService(userService, emailVerificationService);
    }

    private User user(UUID id, String email) {
        return User.builder().id(id).email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build();
    }

    @Test
    void updateEmailChangesAddressAndTriggersReVerification() {
        UUID id = UUID.randomUUID();
        when(userService.findByIdOrThrow(id)).thenReturn(user(id, "old@example.com"));
        when(userService.emailExists("new@example.com")).thenReturn(false);
        User updated = User.builder().id(id).email("new@example.com").emailVerified(false)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build();
        when(userService.updateEmail(id, "new@example.com")).thenReturn(updated);

        MeResponse response = service().updateEmail(id, "  New@Example.com ");

        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.emailVerified()).isFalse();
        verify(emailVerificationService).createAndSend(updated);
    }

    @Test
    void updateEmailIsNoOpWhenUnchanged() {
        UUID id = UUID.randomUUID();
        when(userService.findByIdOrThrow(id)).thenReturn(user(id, "same@example.com"));

        MeResponse response = service().updateEmail(id, "Same@Example.com");

        assertThat(response.email()).isEqualTo("same@example.com");
        verify(userService, never()).updateEmail(any(), anyString());
        verify(emailVerificationService, never()).createAndSend(any());
    }

    @Test
    void updateEmailRejectsAddressAlreadyUsed() {
        UUID id = UUID.randomUUID();
        when(userService.findByIdOrThrow(id)).thenReturn(user(id, "old@example.com"));
        when(userService.emailExists("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service().updateEmail(id, "taken@example.com"))
                .isInstanceOf(EmailAlreadyUsedException.class);
        verify(userService, never()).updateEmail(any(), anyString());
    }

    @Test
    void logoutAllIncrementsTokenVersion() {
        UUID id = UUID.randomUUID();

        service().logoutAllSessions(id);

        verify(userService).incrementTokenVersion(id);
    }
}
