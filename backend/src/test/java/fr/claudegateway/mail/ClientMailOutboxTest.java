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
    @Mock private ClientMailAttachmentStore attachmentStore;

    private ClientMailOutbox outbox;
    private ClientEmail email;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        outbox = new ClientMailOutbox(repository, emailService, attachmentStore, transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
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

    // ---------------------------------------------------------------- pièces jointes (SF-110-03)

    private static final ClientMailMessage.Attachment PDF =
            new ClientMailMessage.Attachment("cr.pdf", "application/pdf", new byte[] {1, 2, 3, 4});

    @Test
    void enqueueWithAttachmentsStoresThemInTheSameTransactionAndCountsTheirSize() {
        ClientMailRenderer.Rendered rendered = ClientMailRenderer.render("# CR", "CAGIP");
        UUID userId = UUID.randomUUID();
        when(repository.save(any(ClientEmail.class))).thenAnswer(inv -> {
            ClientEmail row = inv.getArgument(0);
            row.setId(UUID.randomUUID());
            return row;
        });

        ClientEmail saved = outbox.enqueue(new ClientMailOutbox.Draft(userId, UUID.randomUUID(), null,
                ClientEmail.Kind.AGENT, new ResolvedRecipient("franck@cagip.fr", true, "CAGIP"), "CR", rendered,
                List.of(PDF)));

        assertThat(saved.getAttachmentCount()).isEqualTo(1);
        assertThat(saved.getSizeBytes()).isEqualTo(rendered.sizeBytes() + 4);
        verify(attachmentStore).put(userId, saved.getId(), List.of(PDF));
        verify(transactionManager).commit(any());
    }

    @Test
    void aStorageFailureCancelsTheQueuedLineAndErasesWhatWasWritten() {
        UUID userId = UUID.randomUUID();
        when(repository.save(any(ClientEmail.class))).thenAnswer(inv -> {
            ClientEmail row = inv.getArgument(0);
            row.setId(UUID.randomUUID());
            return row;
        });
        doThrow(new IllegalStateException("s3")).when(attachmentStore).put(any(), any(), any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> outbox.enqueue(new ClientMailOutbox.Draft(userId,
                UUID.randomUUID(), null, ClientEmail.Kind.AGENT, new ResolvedRecipient("franck@cagip.fr", true, "CAGIP"),
                "CR", ClientMailRenderer.render("# CR", "CAGIP"), List.of(PDF))))
                .isInstanceOf(IllegalStateException.class);

        verify(transactionManager).rollback(any());
        verify(attachmentStore).delete(eq(userId), any());
    }

    @Test
    void attachmentsAreSentThenErasedAtTheFinalState() {
        email.setAttachmentCount(1);
        when(attachmentStore.load(email.getUserId(), email.getId())).thenReturn(List.of(PDF));

        outbox.runOnce();

        ArgumentCaptor<ClientMailMessage> message = ArgumentCaptor.forClass(ClientMailMessage.class);
        verify(emailService).sendClientMail(message.capture());
        assertThat(message.getValue().attachments()).containsExactly(PDF);
        assertThat(email.getStatus()).isEqualTo(ClientEmailStatus.SENT);
        verify(attachmentStore).delete(email.getUserId(), email.getId());
    }

    @Test
    void aMissingAttachmentFailsWithoutRetryAndWithoutSending() {
        email.setAttachmentCount(2);
        when(attachmentStore.load(email.getUserId(), email.getId())).thenReturn(List.of(PDF));

        assertThat(outbox.runOnce()).isEqualTo(1);

        verify(emailService, never()).sendClientMail(any());
        assertThat(email.getStatus()).isEqualTo(ClientEmailStatus.FAILED);
        assertThat(email.getFailureReason()).isEqualTo("pièce jointe introuvable");
        assertThat(email.getBodyText()).isNull();
        verify(attachmentStore).delete(email.getUserId(), email.getId());
    }

    @Test
    void attachmentsStayWhileARetryIsPlanned() {
        email.setAttachmentCount(1);
        when(attachmentStore.load(email.getUserId(), email.getId())).thenReturn(List.of(PDF));
        doThrow(new MailSendException("délai dépassé")).when(emailService).sendClientMail(any());

        outbox.runOnce();

        assertThat(email.getStatus()).isEqualTo(ClientEmailStatus.PENDING);
        verify(attachmentStore, never()).delete(any(), any());
    }

    @Test
    void aLineTakenByAnotherPodIsLeftAlone() {
        when(repository.claim(email.getId(), AT, AT.plus(ClientMailOutbox.LEASE))).thenReturn(0);

        assertThat(outbox.runOnce()).isZero();
        verify(emailService, never()).sendClientMail(any());
    }

    // ---------------------------------------------------------------- balayage des orphelines (SF-110-05)

    @Test
    void sweepErasesPiecesWithoutALineAndKeepsPendingAndSendingOnes() {
        UUID vera = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();   // aucune ligne : transaction annulée
        UUID pending = UUID.randomUUID();  // en attente : les pièces servent encore
        UUID sending = UUID.randomUUID();  // sous bail : idem
        when(attachmentStore.listStored()).thenReturn(List.of(
                new ClientMailAttachmentStore.StoredRef(vera, orphan),
                new ClientMailAttachmentStore.StoredRef(vera, pending),
                new ClientMailAttachmentStore.StoredRef(vera, sending)));
        when(repository.findById(orphan)).thenReturn(Optional.empty());
        when(repository.findById(pending)).thenReturn(Optional.of(line(vera, ClientEmailStatus.PENDING)));
        when(repository.findById(sending)).thenReturn(Optional.of(line(vera, ClientEmailStatus.SENDING)));

        assertThat(outbox.sweepOrphans()).isEqualTo(1);

        verify(attachmentStore).delete(vera, orphan);
        verify(attachmentStore, never()).delete(vera, pending);
        verify(attachmentStore, never()).delete(vera, sending);
    }

    @Test
    void sweepErasesPiecesLeftOverFromAFinalMail() {
        UUID vera = UUID.randomUUID();
        UUID sent = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        when(attachmentStore.listStored()).thenReturn(List.of(
                new ClientMailAttachmentStore.StoredRef(vera, sent),
                new ClientMailAttachmentStore.StoredRef(vera, failed)));
        when(repository.findById(sent)).thenReturn(Optional.of(line(vera, ClientEmailStatus.SENT)));
        when(repository.findById(failed)).thenReturn(Optional.of(line(vera, ClientEmailStatus.FAILED)));

        assertThat(outbox.sweepOrphans()).isEqualTo(2);

        verify(attachmentStore).delete(vera, sent);
        verify(attachmentStore).delete(vera, failed);
    }

    @Test
    void sweepDoesNotStopOnAFailedErase() {
        UUID vera = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(attachmentStore.listStored()).thenReturn(List.of(
                new ClientMailAttachmentStore.StoredRef(vera, first),
                new ClientMailAttachmentStore.StoredRef(vera, second)));
        when(repository.findById(first)).thenReturn(Optional.empty());
        when(repository.findById(second)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("stockage indisponible")).when(attachmentStore).delete(vera, first);

        assertThat(outbox.sweepOrphans()).isEqualTo(1);

        verify(attachmentStore).delete(vera, second);
    }

    private static ClientEmail line(UUID userId, ClientEmailStatus status) {
        return ClientEmail.builder().id(UUID.randomUUID()).userId(userId).hostId(UUID.randomUUID())
                .kind(ClientEmail.Kind.AGENT).clientName("CAGIP").recipient("franck@cagip.fr")
                .subject("CR").status(status).attachmentCount(1).build();
    }
}
