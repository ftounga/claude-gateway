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
    void propagatesSmtpFailure() {
        SmtpEmailService service = new SmtpEmailService(mailSender, FROM);
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> service.sendPasswordReset("bob@example.com", "https://portal/reset?t=xyz"))
                .isInstanceOf(MailSendException.class);
    }
}
