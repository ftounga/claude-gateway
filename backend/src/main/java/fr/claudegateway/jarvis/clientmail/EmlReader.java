package fr.claudegateway.jarvis.clientmail;

import java.io.ByteArrayInputStream;
import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import fr.claudegateway.jarvis.mail.InboxReader;
import fr.claudegateway.jarvis.mail.MailText;

/**
 * <b>Lire un {@code .eml} déposé</b> (F-249 / SF-249-01, D6) — par {@code jakarta.mail}, avec <b>la même extraction</b>
 * que la relève IMAP de F-242 ({@link InboxReader#fetched}) : en-têtes, extrait, indice « envoi de masse »,
 * invitation. Le corps est réduit au texte (le HTML n'est jamais rendu), borné à 8 000 caractères.
 */
public final class EmlReader {

    private EmlReader() {
    }

    public static DepositedMail read(byte[] bytes) {
        try {
            MimeMessage m = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes));
            InboxReader.Fetched f = InboxReader.fetched(0, m);
            boolean anything = !f.headers().from().isEmpty() || !f.headers().subject().isEmpty()
                    || f.messageId() != null;
            if (!anything) {
                throw new DepositedMail.UnreadableMailException("Aucun en-tête de courrier.");
            }
            return new DepositedMail(f.messageId(), f.threadRef(), f.headers(), f.receivedAt(), f.bulk(),
                    MailText.text(m, DepositedMail.BODY_MAX), f.calendar());
        } catch (MessagingException | RuntimeException e) {
            if (e instanceof DepositedMail.UnreadableMailException u) {
                throw u;
            }
            throw new DepositedMail.UnreadableMailException("Courrier illisible.");
        }
    }
}
