package fr.claudegateway.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Tests unitaires de l'envoi SMTP réel (prod) : expéditeur, destinataire, présence du lien dans le
 * corps, et propagation d'un échec SMTP. Aucun réseau : {@link JavaMailSender} est mocké.
 */
@ExtendWith(MockitoExtension.class)
class SmtpEmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    private static final String FROM = "no-reply@ng-itconsulting.com";

    @Test
    void sendsVerificationEmailFromConfiguredAddressWithLink() {
        SmtpEmailService service = new SmtpEmailService(mailSender, FROM);

        service.sendEmailVerification("alice@example.com", "https://portal/verify?t=abc");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getFrom()).isEqualTo(FROM);
        assertThat(sent.getTo()).containsExactly("alice@example.com");
        assertThat(sent.getSubject()).contains("Vérifiez");
        assertThat(sent.getText()).contains("https://portal/verify?t=abc");
    }

    @Test
    void sendsPasswordResetEmailWithLink() {
        SmtpEmailService service = new SmtpEmailService(mailSender, FROM);

        service.sendPasswordReset("bob@example.com", "https://portal/reset?t=xyz");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("bob@example.com");
        assertThat(sent.getSubject()).contains("Réinitialisation");
        assertThat(sent.getText()).contains("https://portal/reset?t=xyz");
    }

    @Test
    void sendsTheReceptionAddressCodeNamingTheClient() {
        SmtpEmailService service = new SmtpEmailService(mailSender, FROM);

        service.sendReceptionAddressCode("franck@cagip.fr", "CAGIP", "042917");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getFrom()).isEqualTo(FROM);
        assertThat(sent.getTo()).containsExactly("franck@cagip.fr");
        assertThat(sent.getSubject()).contains("CAGIP").doesNotContain("042917");
        assertThat(sent.getText()).contains("042917").contains("15 minutes");
    }

    @Test
    void sendsTheClientMailAsMultipartAlternativeWithTheDisplayName() throws Exception {
        SmtpEmailService service = new SmtpEmailService(mailSender, FROM);
        jakarta.mail.internet.MimeMessage mime =
                new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()));
        org.mockito.Mockito.when(mailSender.createMimeMessage()).thenReturn(mime);

        service.sendClientMail(new ClientMailMessage("franck@cagip.fr", "claude-gateway pour CAGIP",
                "Compte rendu", "# CR", "<h1>CR</h1>"));

        verify(mailSender).send(mime);
        mime.saveChanges();
        assertThat(mime.getFrom()[0].toString()).contains("claude-gateway pour CAGIP").contains(FROM);
        assertThat(mime.getAllRecipients()).extracting(Object::toString).containsExactly("franck@cagip.fr");
        assertThat(mime.getSubject()).isEqualTo("Compte rendu");
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        mime.writeTo(raw);
        assertThat(raw.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("multipart/alternative", "text/plain", "text/html", "<h1>CR</h1>");
    }

    @Test
    void sendsAttachmentsAlongsideTextAndHtmlWithAnEncodedName() throws Exception {
        SmtpEmailService service = new SmtpEmailService(mailSender, FROM);
        jakarta.mail.internet.MimeMessage mime =
                new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()));
        org.mockito.Mockito.when(mailSender.createMimeMessage()).thenReturn(mime);
        byte[] pdf = {'%', 'P', 'D', 'F', 0, 1, 2};

        service.sendClientMail(new ClientMailMessage("franck@cagip.fr", "claude-gateway pour CAGIP", "CR", "# CR",
                "<h1>CR</h1>", java.util.List.of(new ClientMailMessage.Attachment("Compte rendu réunion.pdf",
                        "application/pdf", pdf))));

        verify(mailSender).send(mime);
        mime.saveChanges();
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        mime.writeTo(raw);
        assertThat(raw.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("multipart/mixed", "multipart/alternative", "application/pdf", "<h1>CR</h1>");
        jakarta.mail.internet.MimeMultipart root = (jakarta.mail.internet.MimeMultipart)
                new jakarta.mail.internet.MimeMessage(null, new java.io.ByteArrayInputStream(raw.toByteArray())).getContent();
        jakarta.mail.BodyPart attachment = null;
        for (int i = 0; i < root.getCount(); i++) {
            if (jakarta.mail.Part.ATTACHMENT.equalsIgnoreCase(root.getBodyPart(i).getDisposition())) {
                attachment = root.getBodyPart(i);
            }
        }
        assertThat(attachment).isNotNull();
        assertThat(jakarta.mail.internet.MimeUtility.decodeText(attachment.getFileName()))
                .isEqualTo("Compte rendu réunion.pdf");
        assertThat(attachment.getInputStream().readAllBytes()).isEqualTo(pdf);
    }

    @Test
    void propagatesSmtpFailure() {
        SmtpEmailService service = new SmtpEmailService(mailSender, FROM);
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> service.sendPasswordReset("bob@example.com", "https://portal/reset?t=xyz"))
                .isInstanceOf(MailSendException.class);
    }
}
