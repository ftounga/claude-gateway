package fr.claudegateway.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Implémentation SMTP réelle de l'{@link EmailService} (prod). Envoie les e-mails transactionnels
 * (vérification d'adresse, réinitialisation de mot de passe) via {@link JavaMailSender}, configuré
 * par {@code spring.mail.*} (secrets {@code MAIL_*}).
 *
 * <p>Active uniquement quand {@code app.email.provider=smtp} ; sinon c'est {@link LoggingEmailService}
 * (dev/tests) qui est retenu. Aucun secret n'est journalisé ; en cas d'échec SMTP on journalise un
 * message neutre sans propager le détail au client.</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.email", name = "provider", havingValue = "smtp")
public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailService(JavaMailSender mailSender, @Value("${app.email.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendEmailVerification(String toEmail, String verificationLink) {
        send(toEmail,
                "Vérifiez votre adresse e-mail",
                "Bonjour,\n\nPour vérifier votre adresse e-mail, cliquez sur le lien suivant :\n"
                        + verificationLink
                        + "\n\nCe lien est à usage unique et expire prochainement.\n\n"
                        + "Si vous n'êtes pas à l'origine de cette demande, ignorez cet e-mail.");
    }

    @Override
    public void sendPasswordReset(String toEmail, String resetLink) {
        send(toEmail,
                "Réinitialisation de votre mot de passe",
                "Bonjour,\n\nPour réinitialiser votre mot de passe, cliquez sur le lien suivant :\n"
                        + resetLink
                        + "\n\nCe lien est à usage unique et expire prochainement.\n\n"
                        + "Si vous n'êtes pas à l'origine de cette demande, ignorez cet e-mail : "
                        + "votre mot de passe reste inchangé.");
    }

    private void send(String toEmail, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
            // Aucune donnée sensible ni contenu n'est journalisé : trace opérationnelle minimale.
            log.info("E-mail transactionnel envoyé (sujet={})", subject);
        } catch (MailException ex) {
            // On ne propage pas le détail SMTP au client ; l'échec est journalisé sans le lien/token.
            log.warn("Échec de l'envoi d'un e-mail transactionnel (sujet={})", subject);
            throw ex;
        }
    }
}
