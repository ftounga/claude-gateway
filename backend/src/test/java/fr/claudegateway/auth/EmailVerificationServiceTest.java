package fr.claudegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.email.EmailService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRole;
import fr.claudegateway.user.UserService;

/**
 * Tests unitaires de {@link EmailVerificationService} : création/envoi du token et validation
 * (nominal, inconnu, expiré, déjà consommé). Collaborateurs mockés.
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock
    private EmailVerificationTokenRepository tokenRepository;
    @Mock
    private SecureTokenGenerator tokenGenerator;
    @Mock
    private EmailService emailService;
    @Mock
    private UserService userService;

    private EmailVerificationService service() {
        return new EmailVerificationService(
                tokenRepository, tokenGenerator, emailService, userService,
                "http://localhost:4200", Duration.ofHours(24));
    }

    private User user(String email) {
        return User.builder().id(UUID.randomUUID()).email(email)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build();
    }

    @Test
    void createAndSendPersistsTokenAndSendsLink() {
        User user = user("alice@example.com");
        when(tokenGenerator.generate()).thenReturn("RAW-TOKEN");

        service().createAndSend(user);

        ArgumentCaptor<EmailVerificationToken> saved = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(user.getId());
        assertThat(saved.getValue().getToken()).isEqualTo("RAW-TOKEN");
        assertThat(saved.getValue().getExpiresAt()).isAfter(OffsetDateTime.now());

        verify(emailService).sendEmailVerification(
                eq("alice@example.com"), eq("http://localhost:4200/auth/verify?token=RAW-TOKEN"));
    }

    @Test
    void verifyMarksEmailVerifiedAndConsumesToken() {
        UUID userId = UUID.randomUUID();
        EmailVerificationToken token = EmailVerificationToken.builder()
                .userId(userId).token("VALID")
                .expiresAt(OffsetDateTime.now().plusHours(1)).build();
        when(tokenRepository.findByToken("VALID")).thenReturn(Optional.of(token));
        when(userService.markEmailVerified(userId)).thenReturn(user("bob@example.com"));

        service().verify("VALID");

        verify(userService).markEmailVerified(userId);
        ArgumentCaptor<EmailVerificationToken> saved = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(saved.capture());
        assertThat(saved.getValue().getUsedAt()).isNotNull();
    }

    @Test
    void verifyRejectsUnknownToken() {
        when(tokenRepository.findByToken("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().verify("NOPE"))
                .isInstanceOf(InvalidVerificationTokenException.class);
        verify(userService, never()).markEmailVerified(any());
    }

    @Test
    void verifyRejectsExpiredToken() {
        EmailVerificationToken token = EmailVerificationToken.builder()
                .userId(UUID.randomUUID()).token("OLD")
                .expiresAt(OffsetDateTime.now().minusMinutes(1)).build();
        when(tokenRepository.findByToken("OLD")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service().verify("OLD"))
                .isInstanceOf(InvalidVerificationTokenException.class);
        verify(userService, never()).markEmailVerified(any());
    }

    @Test
    void verifyRejectsAlreadyConsumedToken() {
        EmailVerificationToken token = EmailVerificationToken.builder()
                .userId(UUID.randomUUID()).token("USED")
                .expiresAt(OffsetDateTime.now().plusHours(1))
                .usedAt(OffsetDateTime.now().minusMinutes(5)).build();
        when(tokenRepository.findByToken("USED")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service().verify("USED"))
                .isInstanceOf(InvalidVerificationTokenException.class);
        verify(userService, never()).markEmailVerified(any());
    }
}
