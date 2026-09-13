package fr.claudegateway.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.email.EmailService;
import fr.claudegateway.runner.TokenHasher;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostNotFoundException;
import fr.claudegateway.runner.host.RunnerHostService;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;

/** L'adresse de réception d'un client, et la résolution du destinataire (F-110 / SF-110-01). */
@ExtendWith(MockitoExtension.class)
class HostMailAddressServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    @Mock private HostMailAddressRepository repository;
    @Mock private RunnerHostService hostService;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;

    private final TokenHasher hasher = new TokenHasher();
    private final UUID alice = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final AtomicReference<HostMailAddress> stored = new AtomicReference<>();
    private final AtomicReference<String> sentCode = new AtomicReference<>();
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        RunnerHost host = RunnerHost.builder().id(hostId).userId(alice).name("CAGIP").build();
        lenient().when(hostService.requireOwned(alice, hostId)).thenReturn(host);
        lenient().when(userRepository.findById(alice))
                .thenReturn(Optional.of(User.builder().id(alice).email("ntounga@gmail.com").build()));
        lenient().when(repository.findByUserIdAndHostId(alice, hostId)).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        lenient().when(repository.save(any(HostMailAddress.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        lenient().doAnswer(inv -> {
            sentCode.set(inv.getArgument(2));
            return null;
        }).when(emailService).sendReceptionAddressCode(anyString(), anyString(), anyString());
    }

    private HostMailAddressService service() {
        return new HostMailAddressService(repository, hostService, userRepository, emailService, hasher, clock);
    }

    private void advance(long seconds) {
        clock = Clock.fixed(clock.instant().plusSeconds(seconds), ZoneOffset.UTC);
    }

    @Test
    void declaringStoresTheAddressUnverifiedAndSendsASixDigitCodeKeepingOnlyItsHash() {
        HostMailAddressView view = service().declare(alice, hostId, " Franck.Tounga@CAGIP.fr ");

        assertThat(sentCode.get()).matches("\\d{6}");
        verify(emailService).sendReceptionAddressCode("franck.tounga@cagip.fr", "CAGIP", sentCode.get());
        assertThat(stored.get().getCodeHash()).isEqualTo(hasher.sha256Hex(sentCode.get()))
                .isNotEqualTo(sentCode.get());
        assertThat(stored.get().getCodeExpiresAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(15));
        assertThat(view.verified()).isFalse();
        assertThat(view.codePending()).isTrue();
        assertThat(view.recipient()).isEqualTo("ntounga@gmail.com");
        assertThat(view.fallback()).isTrue();
    }

    @Test
    void theRightCodeVerifiesTheAddressAndMakesItTheRecipient() {
        service().declare(alice, hostId, "franck.tounga@cagip.fr");

        HostMailAddressView view = service().verify(alice, hostId, sentCode.get());

        assertThat(view.verified()).isTrue();
        assertThat(view.recipient()).isEqualTo("franck.tounga@cagip.fr");
        assertThat(view.fallback()).isFalse();
        assertThat(stored.get().getCodeHash()).isNull();
        assertThat(service().resolveRecipient(alice, hostId))
                .isEqualTo(new ResolvedRecipient("franck.tounga@cagip.fr", true, "CAGIP"));
    }

    @Test
    void aWrongCodeIsRefusedAndTheFifthFailureVoidsTheCode() {
        service().declare(alice, hostId, "franck.tounga@cagip.fr");
        String wrong = sentCode.get().equals("000000") ? "111111" : "000000";

        for (int i = 1; i < HostMailAddressService.MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> service().verify(alice, hostId, wrong))
                    .isInstanceOfSatisfying(MailAddressException.class,
                            ex -> assertThat(ex.code()).isEqualTo("mail_code_invalid"));
        }
        assertThatThrownBy(() -> service().verify(alice, hostId, wrong))
                .hasMessageContaining("n'est plus valable");
        // Le bon code ne vaut plus rien après l'épuisement.
        assertThatThrownBy(() -> service().verify(alice, hostId, sentCode.get()))
                .isInstanceOfSatisfying(MailAddressException.class,
                        ex -> assertThat(ex.code()).isEqualTo("mail_code_expired"));
        assertThat(service().resolveRecipient(alice, hostId).verifiedForClient()).isFalse();
    }

    @Test
    void anExpiredCodeIsRefused() {
        service().declare(alice, hostId, "franck.tounga@cagip.fr");
        advance(16 * 60);

        assertThatThrownBy(() -> service().verify(alice, hostId, sentCode.get()))
                .isInstanceOfSatisfying(MailAddressException.class,
                        ex -> assertThat(ex.code()).isEqualTo("mail_code_expired"));
    }

    @Test
    void changingAVerifiedAddressRestartsVerificationAndFallsBackMeanwhile() {
        service().declare(alice, hostId, "franck.tounga@cagip.fr");
        service().verify(alice, hostId, sentCode.get());
        advance(120);

        HostMailAddressView view = service().declare(alice, hostId, "franck@autre.fr");

        assertThat(view.verified()).isFalse();
        assertThat(view.address()).isEqualTo("franck@autre.fr");
        assertThat(service().resolveRecipient(alice, hostId))
                .isEqualTo(new ResolvedRecipient("ntounga@gmail.com", false, "CAGIP"));
    }

    @Test
    void redeclaringTheVerifiedAddressChangesNothingAndSendsNoCode() {
        service().declare(alice, hostId, "franck.tounga@cagip.fr");
        service().verify(alice, hostId, sentCode.get());

        HostMailAddressView view = service().declare(alice, hostId, "FRANCK.TOUNGA@cagip.fr");

        assertThat(view.verified()).isTrue();
        verify(emailService, org.mockito.Mockito.times(1)).sendReceptionAddressCode(anyString(), anyString(), anyString());
    }

    @Test
    void aSecondCodeWithinAMinuteIsThrottledWhateverTheAddress() {
        service().declare(alice, hostId, "franck.tounga@cagip.fr");

        assertThatThrownBy(() -> service().resend(alice, hostId))
                .isInstanceOfSatisfying(MailAddressException.class,
                        ex -> assertThat(ex.code()).isEqualTo("mail_code_throttled"));
        assertThatThrownBy(() -> service().declare(alice, hostId, "quelquun@ailleurs.fr"))
                .isInstanceOfSatisfying(MailAddressException.class,
                        ex -> assertThat(ex.code()).isEqualTo("mail_code_throttled"));

        advance(61);
        String first = sentCode.get();
        service().resend(alice, hostId);
        assertThat(stored.get().getCodeHash()).isEqualTo(hasher.sha256Hex(sentCode.get()));
        if (!first.equals(sentCode.get())) {
            assertThatThrownBy(() -> service().verify(alice, hostId, first))
                    .isInstanceOf(MailAddressException.class);
        }
    }

    @Test
    void resendOrVerifyWithoutPendingAddressIsAConflict() {
        assertThatThrownBy(() -> service().resend(alice, hostId))
                .isInstanceOfSatisfying(MailAddressException.class,
                        ex -> assertThat(ex.code()).isEqualTo("mail_code_none"));
        assertThatThrownBy(() -> service().verify(alice, hostId, "123456"))
                .isInstanceOfSatisfying(MailAddressException.class,
                        ex -> assertThat(ex.code()).isEqualTo("mail_code_none"));
    }

    @Test
    void aRelayFailureIsSaidAndDoesNotThrottleTheRetry() {
        doThrow(new org.springframework.mail.MailSendException("relais"))
                .when(emailService).sendReceptionAddressCode(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service().declare(alice, hostId, "franck.tounga@cagip.fr"))
                .isInstanceOfSatisfying(MailAddressException.class,
                        ex -> assertThat(ex.code()).isEqualTo("mail_code_not_sent"));
        assertThat(stored.get().getAddress()).isEqualTo("franck.tounga@cagip.fr");
        assertThat(stored.get().getCodeSentAt()).isNull();
    }

    @Test
    void anInvalidAddressIsRefusedBeforeAnything() {
        assertThatThrownBy(() -> service().declare(alice, hostId, "pas-une-adresse"))
                .isInstanceOf(InvalidMailAddressException.class);
        verify(repository, never()).save(any());
        verify(emailService, never()).sendReceptionAddressCode(anyString(), anyString(), anyString());
    }

    @Test
    void thePendingAddressIsNeverTheRecipient() {
        service().declare(alice, hostId, "franck.tounga@cagip.fr");

        assertThat(service().resolveRecipient(alice, hostId))
                .isEqualTo(new ResolvedRecipient("ntounga@gmail.com", false, "CAGIP"));
    }

    @Test
    void removingFallsBackToTheAccountAddress() {
        service().declare(alice, hostId, "franck.tounga@cagip.fr");
        service().verify(alice, hostId, sentCode.get());
        org.mockito.Mockito.doAnswer(inv -> {
            stored.set(null);
            return null;
        }).when(repository).delete(any(HostMailAddress.class));

        HostMailAddressView view = service().remove(alice, hostId);

        assertThat(view.address()).isNull();
        assertThat(view.fallback()).isTrue();
        assertThat(service().resolveRecipient(alice, hostId).address()).isEqualTo("ntounga@gmail.com");
    }

    @Test
    void anotherUsersHostIsNotFound() {
        UUID bob = UUID.randomUUID();
        when(hostService.requireOwned(eq(bob), eq(hostId))).thenThrow(new RunnerHostNotFoundException("x"));

        assertThatThrownBy(() -> service().view(bob, hostId)).isInstanceOf(RunnerHostNotFoundException.class);
        assertThatThrownBy(() -> service().resolveRecipient(bob, hostId))
                .isInstanceOf(RunnerHostNotFoundException.class);
        verify(repository, never()).findByUserIdAndHostId(eq(bob), any());
    }
}
