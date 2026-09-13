package fr.claudegateway.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import fr.claudegateway.email.ClientMailMessage;
import fr.claudegateway.email.EmailService;

/** La file des courriels du client : envoi, refus, reprise, bail, effacement du corps (F-110 / SF-110-02). */
@ExtendWith(MockitoExtension.class)
class ClientMailOutboxTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private static final OffsetDateTime AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock private ClientEmailRepository repository;
    @Mock private EmailService emailService;
    @Mock private PlatformTransactionManager transactionManager;

    private ClientMailOutbox outbox;
    private ClientEmail email;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        outbox = new ClientMailOutbox(repository, emailService, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
        email = ClientEmail.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).hostId(UUID.randomUUID())
                .kind(ClientEmail.Kind.AGENT).clientName("CAGIP").recipient("franck@cagip.fr").recipientVerified(true)
                .subject("Compte rendu").sizeBytes(120).bodyText("# CR").bodyHtml("<h1>CR</h1>")
                .status(ClientEmailStatus.SENDING).attempts(1).nextAttemptAt(AT).leasedUntil(AT.plusMinutes(2)).build();
        lenient().when(repository.findDue(eq(AT), any())).thenReturn(List.of(email.getId()));
        lenient().when(repository.claim(email.getId(), AT, AT.plus(ClientMailOutbox.LEASE))).thenReturn(1);
        lenient().when(repository.findById(email.getId())).thenReturn(Optional.of(email));
        lenient().when(repository.save(any(ClientEmail.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void enqueueWritesAPendingLineDueNow() {
        ClientMailRenderer.Rendered rendered = ClientMailRenderer.render("# CR", "CAGIP");
        UUID userId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();

        outbox.enqueue(new ClientMailOutbox.Draft(userId, hostId, null, ClientEmail.Kind.AGENT,
                new ResolvedRecipient("ntounga@gmail.com", false, "CAGIP"), "CR", rendered));

        ArgumentCaptor<ClientEmail> saved = ArgumentCaptor.forClass(ClientEmail.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ClientEmailStatus.PENDING);
        assertThat(saved.getValue().getNextAttemptAt()).isEqualTo(AT);
        assertThat(saved.getValue().isRecipientVerified()).isFalse();
        assertThat(saved.getValue().getSizeBytes()).isEqualTo(rendered.sizeBytes());
        assertThat(saved.getValue().getBodyHtml()).contains("<h1>CR</h1>");
    }

    @Test
    void anAcceptedMailIsSentWithTheDisplayNameAndItsBodyIsErased() {
        assertThat(outbox.runOnce()).isEqualTo(1);

        ArgumentCaptor<ClientMailMessage> message = ArgumentCaptor.forClass(ClientMailMessage.class);
        verify(emailService).sendClientMail(message.capture());
        assertThat(message.getValue()).isEqualTo(new ClientMailMessage("franck@cagip.fr", "claude-gateway pour CAGIP",
                "Compte rendu", "# CR", "<h1>CR</h1>"));
        assertThat(email.getStatus()).isEqualTo(ClientEmailStatus.SENT);
        assertThat(email.getSentAt()).isEqualTo(AT);
        assertThat(email.getBodyText()).isNull();
        assertThat(email.getBodyHtml()).isNull();
        assertThat(email.getLeasedUntil()).isNull();
    }

    @Test
    void aRefusedAddressFailsAtOnceWithoutRetry() {
        doThrow(new MailSendException("refus", new jakarta.mail.SendFailedException("550 unknown user")))
                .when(emailService).sendClientMail(any());

        outbox.runOnce();

        assertThat(email.getStatus()).isEqualTo(ClientEmailStatus.FAILED);
        assertThat(email.getFailureReason()).isEqualTo("adresse refusée par le relais");
        assertThat(email.getBodyText()).isNull();
    }

    @Test
    void aRefusalNestedInFailedMessagesIsPermanentToo() {
        doThrow(new MailSendException(Map.of(new Object(), new jakarta.mail.SendFailedException("550"))))
                .when(emailService).sendClientMail(any());

        outbox.runOnce();

        assertThat(email.getStatus()).isEqualTo(ClientEmailStatus.FAILED);
    }

    @Test
    void anInvalidMessageFailsAtOnce() {
        doThrow(new MailPreparationException("invalide")).when(emailService).sendClientMail(any());

        outbox.runOnce();

        assertThat(email.getStatus()).isEqualTo(ClientEmailStatus.FAILED);
        assertThat(email.getFailureReason()).isEqualTo("message invalide");
    }

    @Test
    void aTransientFailureIsRetriedLaterThenFailsAtTheFifthAttempt() {
        doThrow(new MailSendException("délai dépassé")).when(emailService).sendClientMail(any());

        outbox.runOnce();

        assertThat(email.getStatus()).isEqualTo(ClientEmailStatus.PENDING);
        assertThat(email.getNextAttemptAt()).isEqualTo(AT.plusMinutes(1));
        assertThat(email.getFailureReason()).isEqualTo("relais injoignable");
        assertThat(email.getBodyText()).as("le corps reste tant qu'une reprise est prévue").isEqualTo("# CR");

        email.setAttempts(3);
        email.setStatus(ClientEmailStatus.SENDING);
        outbox.runOnce();
        assertThat(email.getNextAttemptAt()).isEqualTo(AT.plusMinutes(15));

        email.setAttempts(ClientMailOutbox.MAX_ATTEMPTS);
        email.setStatus(ClientEmailStatus.SENDING);
        outbox.runOnce();
        assertThat(email.getStatus()).isEqualTo(ClientEmailStatus.FAILED);
        assertThat(email.getBodyText()).isNull();
    }

    @Test
    void aLineTakenByAnotherPodIsLeftAlone() {
        when(repository.claim(email.getId(), AT, AT.plus(ClientMailOutbox.LEASE))).thenReturn(0);

        assertThat(outbox.runOnce()).isZero();
        verify(emailService, never()).sendClientMail(any());
    }
}
