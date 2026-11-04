package fr.claudegateway.jarvis.mail;

import java.util.UUID;

import org.springframework.stereotype.Service;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.UIDFolder;

/**
 * <b>Lire un courrier en entier</b> (F-242 / SF-242-04, SF-242-05) — le corps est <b>relu par IMAP</b> à la demande
 * (jamais stocké, D5), en lecture seule (aucun drapeau « lu » posé), borné, et réduit au <b>texte</b> : le HTML n'est
 * jamais rendu, donc aucune image distante (pixel espion) ni aucun script ne peut se charger (D16). Une pièce jointe
 * n'est jamais ouverte (D9 e).
 */
@Service
public class JarvisMailReader {

    /** Le corps borné d'un courrier, avec sa vue d'en-têtes. */
    public record Body(JarvisMailItemService.ItemView item, String text, boolean truncated) {
    }

    public static final int BODY_MAX = 8_000;

    private final JarvisMailItemService items;
    private final JarvisMailAccountService accounts;
    private final JarvisMailServer server;

    public JarvisMailReader(JarvisMailItemService items, JarvisMailAccountService accounts, JarvisMailServer server) {
        this.items = items;
        this.accounts = accounts;
        this.server = server;
    }

    /** Le courrier du compte, corps relu par IMAP (≈ 8 000 caractères au plus). 404 s'il n'est plus dans la boîte. */
    public Body read(UUID userId, UUID itemId) {
        JarvisMailItem item = items.require(userId, itemId);
        JarvisMailAccount account = accounts.require(userId, item.getAccountId());
        String text = server.withStore(account.getAddress(), accounts.secret(account), store -> {
            Folder folder = store.getFolder("INBOX");
            if (!(folder instanceof UIDFolder uids)) {
                return null;
            }
            folder.open(Folder.READ_ONLY);
            try {
                if (uids.getUIDValidity() != item.getUidvalidity()) {
                    return null;
                }
                Message m = uids.getMessageByUID(item.getUid());
                return m == null ? null : MailText.text(m, BODY_MAX + 1);
            } finally {
                folder.close(false);
            }
        });
        if (text == null) {
            throw new JarvisMailExceptions.MailNotFoundException();
        }
        boolean truncated = text.length() > BODY_MAX;
        JarvisMailItemService.ItemView view = items.views(userId, java.util.List.of(item)).get(0);
        return new Body(view, truncated ? text.substring(0, BODY_MAX) : text, truncated);
    }
}
