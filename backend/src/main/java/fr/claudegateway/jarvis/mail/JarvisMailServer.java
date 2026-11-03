package fr.claudegateway.jarvis.mail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.eclipse.angus.mail.imap.IMAPFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;

/**
 * <b>Le relais IMAP / SMTP</b> (F-242, Gateway-First) — la passerelle se connecte à la boîte du PO avec son mot de
 * passe d'application et <b>relaie</b> : aucun moteur de compréhension du courrier ici. jakarta.mail (Angus) est déjà
 * apporté par {@code spring-boot-starter-mail} : aucune dépendance nouvelle.
 *
 * <p>Le mot de passe n'est jamais journalisé ; une erreur n'expose que sa classe. Un refus d'authentification devient
 * {@link JarvisMailExceptions.MailAuthException}, tout le reste {@link JarvisMailExceptions.MailUnavailableException}.</p>
 *
 * <p><b>Rien d'irréversible</b> (§3 du cadrage) : ce composant n'envoie jamais {@code EXPUNGE}.</p>
 */
@Component
public class JarvisMailServer {

    private static final Logger log = LoggerFactory.getLogger(JarvisMailServer.class);

    /** Un travail sur une boîte IMAP ouverte (le store est fermé après). */
    @FunctionalInterface
    public interface StoreWork<T> {
        T run(Store store) throws MessagingException;
    }

    private final JarvisMailProperties properties;

    public JarvisMailServer(JarvisMailProperties properties) {
        this.properties = properties;
    }

    /** Une session jakarta.mail aux réglages du préréglage (délais bornés, identité du serveur vérifiée). */
    public Session session() {
        Properties p = new Properties();
        String imap = imapProtocol();
        String smtp = smtpProtocol();
        String timeout = String.valueOf(properties.timeout().toMillis());
        p.put("mail.store.protocol", imap);
        p.put("mail." + imap + ".host", properties.imapHost());
        p.put("mail." + imap + ".port", String.valueOf(properties.imapPort()));
        p.put("mail." + imap + ".connectiontimeout", timeout);
        p.put("mail." + imap + ".timeout", timeout);
        p.put("mail." + imap + ".writetimeout", timeout);
        p.put("mail.transport.protocol", smtp);
        p.put("mail." + smtp + ".host", properties.smtpHost());
        p.put("mail." + smtp + ".port", String.valueOf(properties.smtpPort()));
        p.put("mail." + smtp + ".auth", "true");
        p.put("mail." + smtp + ".connectiontimeout", timeout);
        p.put("mail." + smtp + ".timeout", timeout);
        p.put("mail." + smtp + ".writetimeout", timeout);
        if (properties.isSsl()) {
            p.put("mail.imaps.ssl.checkserveridentity", "true");
            p.put("mail.smtps.ssl.checkserveridentity", "true");
        }
        return Session.getInstance(p);
    }

    /** Ouvre la boîte, exécute le travail, referme. */
    public <T> T withStore(String address, String password, StoreWork<T> work) {
        Store store = null;
        try {
            store = session().getStore(imapProtocol());
            store.connect(properties.imapHost(), properties.imapPort(), address, password);
            return work.run(store);
        } catch (AuthenticationFailedException e) {
            throw new JarvisMailExceptions.MailAuthException();
        } catch (MessagingException e) {
            log.info("Courrier : IMAP indisponible ({})", e.getClass().getSimpleName());
            throw new JarvisMailExceptions.MailUnavailableException();
        } finally {
            close(store);
        }
    }

    /** Vérifie l'authentification SMTP, sans rien envoyer. */
    public void verifySmtp(String address, String password) {
        Transport transport = null;
        try {
            transport = session().getTransport(smtpProtocol());
            transport.connect(properties.smtpHost(), properties.smtpPort(), address, password);
        } catch (AuthenticationFailedException e) {
            throw new JarvisMailExceptions.MailAuthException();
        } catch (MessagingException e) {
            log.info("Courrier : SMTP indisponible ({})", e.getClass().getSimpleName());
            throw new JarvisMailExceptions.MailUnavailableException();
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                } catch (MessagingException ignored) {
                    // fermeture best-effort
                }
            }
        }
    }

    /** Envoie un message préparé avec la session de {@link #session()} (F-242 / SF-242-06). */
    public void send(String address, String password, MimeMessage message) {
        Transport transport = null;
        try {
            transport = session().getTransport(smtpProtocol());
            transport.connect(properties.smtpHost(), properties.smtpPort(), address, password);
            transport.sendMessage(message, message.getAllRecipients());
        } catch (AuthenticationFailedException e) {
            throw new JarvisMailExceptions.MailAuthException();
        } catch (MessagingException e) {
            log.info("Courrier : envoi SMTP en échec ({})", e.getClass().getSimpleName());
            throw new JarvisMailExceptions.MailUnavailableException();
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                } catch (MessagingException ignored) {
                    // fermeture best-effort
                }
            }
        }
    }

    /** Relier ou tester : IMAP (connexion + dossiers) puis SMTP (authentification). Rend les dossiers trouvés. */
    public MailFolders verify(String address, String password) {
        MailFolders folders = withStore(address, password, JarvisMailServer::folders);
        verifySmtp(address, password);
        return folders;
    }

    /** Les dossiers de la boîte ouverte, par SPECIAL-USE (D3). */
    public static MailFolders folders(Store store) throws MessagingException {
        List<MailFolders.FolderInfo> infos = new ArrayList<>();
        for (Folder f : store.getDefaultFolder().list("*")) {
            List<String> attributes = f instanceof IMAPFolder imap && imap.getAttributes() != null
                    ? Arrays.asList(imap.getAttributes()) : List.of();
            infos.add(new MailFolders.FolderInfo(f.getFullName(), attributes));
        }
        return MailFolders.discover(infos);
    }

    private String imapProtocol() {
        return properties.isSsl() ? "imaps" : "imap";
    }

    private String smtpProtocol() {
        return properties.isSsl() ? "smtps" : "smtp";
    }

    private static void close(Store store) {
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (MessagingException ignored) {
                // fermeture best-effort
            }
        }
    }
}
