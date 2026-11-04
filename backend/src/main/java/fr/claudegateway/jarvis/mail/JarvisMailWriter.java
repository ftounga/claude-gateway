package fr.claudegateway.jarvis.mail;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * <b>Les écritures sur une boîte</b> (F-242 / SF-242-06, D12-D15) — brouillon (APPEND dans {@code \Drafts} avec le drapeau
 * {@code \Draft}), envoi SMTP avec le compte de la boîte (aucun APPEND dans « Envoyés » : Gmail le range lui-même),
 * archivage (MOVE de la boîte de réception vers {@code \All}) et corbeille (MOVE vers {@code \Trash}).
 *
 * <p><b>Rien d'irréversible</b> : jamais {@code \Deleted}, jamais {@code EXPUNGE} — un déplacement IMAP {@code MOVE}
 * (RFC 6851), et c'est tout. Les gestes qui engagent n'arrivent ici <b>qu'après</b> le « oui » vérifié
 * ({@link JarvisMailActionService}).</p>
 */
@Service
public class JarvisMailWriter {

    /** Un message à composer. */
    public record Draft(String from, List<String> to, List<String> cc, String subject, String body, String inReplyTo,
            String references) {
    }

    private final JarvisMailAccountService accounts;
    private final JarvisMailServer server;

    public JarvisMailWriter(JarvisMailAccountService accounts, JarvisMailServer server) {
        this.accounts = accounts;
        this.server = server;
    }

    /** Compose le message MIME (texte UTF-8, en-têtes de fil s'il y a lieu). */
    public MimeMessage compose(Session session, Draft d) throws MessagingException {
        MimeMessage m = new MimeMessage(session);
        m.setFrom(new InternetAddress(d.from()));
        for (String to : d.to()) {
            m.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
        }
        for (String cc : d.cc()) {
            m.addRecipient(Message.RecipientType.CC, new InternetAddress(cc));
        }
        m.setSubject(d.subject(), "UTF-8");
        m.setText(d.body(), "UTF-8");
        m.setSentDate(new Date());
        if (d.inReplyTo() != null && !d.inReplyTo().isBlank()) {
            m.setHeader("In-Reply-To", d.inReplyTo());
            m.setHeader("References", d.references() == null || d.references().isBlank() ? d.inReplyTo()
                    : d.references());
        }
        m.saveChanges();
        return m;
    }

    /** Dépose un brouillon dans le dossier SPECIAL-USE {@code \Drafts}. Rend le nom du dossier. */
    public String appendDraft(UUID userId, UUID accountId, Draft d) {
        JarvisMailAccount account = accounts.require(userId, accountId);
        return server.withStore(account.getAddress(), accounts.secret(account), store -> {
            MailFolders folders = JarvisMailServer.folders(store);
            if (folders.drafts() == null) {
                throw new JarvisMailExceptions.MailNotFoundException();
            }
            Folder drafts = store.getFolder(folders.drafts());
            MimeMessage m = compose(server.session(), d);
            m.setFlag(Flags.Flag.DRAFT, true);
            drafts.appendMessages(new Message[] {m});
            return folders.drafts();
        });
    }

    /** Envoie par SMTP avec le compte de la boîte (après le « oui » vérifié). */
    public void send(UUID userId, UUID accountId, Draft d) {
        JarvisMailAccount account = accounts.require(userId, accountId);
        String secret = accounts.secret(account);
        MimeMessage m;
        try {
            m = compose(server.session(), new Draft(account.getAddress(), d.to(), d.cc(), d.subject(), d.body(),
                    d.inReplyTo(), d.references()));
        } catch (MessagingException e) {
            throw new JarvisMailExceptions.InvalidMailAccountException("Message impossible à composer.");
        }
        server.send(account.getAddress(), secret, m);
    }

    /** Archive (vers {@code \All}) ou met à la corbeille (vers {@code \Trash}) : un MOVE, jamais d'EXPUNGE. */
    public int move(UUID userId, UUID accountId, List<MailRef> refs, boolean trash) {
        JarvisMailAccount account = accounts.require(userId, accountId);
        return server.withStore(account.getAddress(), accounts.secret(account), store -> {
            // Sans l'extension MOVE, un déplacement serait COPY + drapeau Deleted + EXPUNGE : refusé, jamais tenté.
            if (!(store instanceof org.eclipse.angus.mail.imap.IMAPStore imap) || !imap.hasCapability("MOVE")) {
                throw new JarvisMailExceptions.InvalidMailAccountException(
                        "Cette boîte ne sait pas déplacer un courriel sans le supprimer : rien n'est fait.");
            }
            MailFolders folders = JarvisMailServer.folders(store);
            String target = trash ? folders.trash() : folders.all();
            if (target == null) {
                throw new JarvisMailExceptions.MailNotFoundException();
            }
            int moved = 0;
            for (boolean all : new boolean[] {false, true}) {
                List<MailRef> group = refs.stream().filter(r -> r.all() == all).toList();
                if (group.isEmpty()) {
                    continue;
                }
                Folder source = JarvisMailBox.folder(store, all);
                if (source.getFullName().equals(target)) {
                    continue;
                }
                source.open(Folder.READ_WRITE);
                try {
                    UIDFolder uids = (UIDFolder) source;
                    List<Message> messages = new ArrayList<>();
                    for (MailRef r : group) {
                        Message m = uids.getUIDValidity() == r.uidvalidity() ? uids.getMessageByUID(r.uid()) : null;
                        if (m != null) {
                            messages.add(m);
                        }
                    }
                    if (!messages.isEmpty()) {
                        // MOVE (RFC 6851) : le serveur déplace ; aucun \Deleted posé par la passerelle, aucun EXPUNGE.
                        ((org.eclipse.angus.mail.imap.IMAPFolder) source)
                                .moveMessages(messages.toArray(Message[]::new), store.getFolder(target));
                        moved += messages.size();
                    }
                } finally {
                    source.close(false);
                }
            }
            return moved;
        });
    }
}
